# Lessons: Learn From the App, Not the Book — Design

Date: 2026-10-30
Status: approved
Builds on: `docs/superpowers/specs/2026-10-17-placement-revision-app-design.md` (the existing app)

## 1. Intent

The user's reframing of the app:

> This application is meant to be a learning enabler for me. Whenever I want to learn something, I should put the book, and once I'm done with learning and revision, I know everything in the book.

Reading a book cover to cover is long, boring and slow, and books carry filler and examples you don't need. So the app should **teach** each section, not just point at pages.

**Change from the original spec:** the original app deliberately did not teach. It gave a map ("read pp. 13–15") and an examiner (revision). This design adds the missing middle: a **lesson** for every section. Reading the book becomes optional, and the book stays one click away.

**Success:** for any book in the app, finishing every lesson and keeping up with revision means the user knows everything in the book that matters.

## 2. Goals, non-goals, anti-goals

### Goals

- Clicking an Up next item opens a **lesson** for that section. A lesson takes about 5–10 minutes and contains:
  - **Text**: the key ideas in plain words.
  - **Diagrams**, where they help (boxes and arrows showing how parts connect).
  - **A YouTube reference** for the topic.
  - **Connections** to topics the user has already learned.
  - **A quick check**: questions, plus a mini design for system design material.
- **Lessons cover everything that matters in the section**, because they replace reading.
- **A finish line per book**: the book is "Mastered" when every point is known well.
- **Existing books get lessons too**: Alex Xu and *Head First Java* are already processed, and the user's progress in them must be kept.

### The line between "everything" and "unnecessary" (decided with the user)

- **Kept:** every concept, trade-off, number and gotcha, plus **the one example that makes each idea click**.
- **Dropped:** stories and anecdotes, repeated introductions and recaps, code listings that only repeat the explanation, and further examples after the first.

### Non-goals

- Teaching anything beyond the book. The uploaded books stay the syllabus.
- Choosing or vouching for specific videos. The app can't watch them.
- Reading the book's figures or images. The AI only receives text (see §3).
- Replacing revision. Lessons teach; the existing revision loop still makes things stick.

### Anti-goals (would make this a failure even if it works)

- **A lesson that invents or gets wrong** something the book doesn't say. Lessons must be grounded in the pages, like points and grading already are.
- **A lesson that silently leaves out something important.** That breaks "know everything in the book". Omissions must be visible.
- **A lesson nearly as long as the pages**, which is no better than reading.
- **A long wait** to open a lesson.

## 3. Constraints

- **Existing stack:**
  - Spring Boot 4 backend, React 19 + Vite frontend, file-based H2 in `data/`, runs on the laptop.
  - Groq: `openai/gpt-oss-120b` across 13 keys, about 8,000 tokens per minute per key.
  - Every AI request is kept under a ~16,000-character budget (`IngestionService.DEFAULT_MAX_INPUT_CHARS`).
  - Lesson generation must obey the same budget, splitting long sections the way point extraction already does.
- **No vision model.** The Groq models available are text-only, so diagrams are drawn from the text, not copied from the book.
- **The user's existing data must survive.** That includes the books already processed, sections learned, revision schedules and switches. Schema changes are additive, since `ddl-auto=update` adds columns and tables but never alters them. Enum-like columns are plain varchar, as established in the earlier fix.
- **Existing behaviours stay** unless this design changes them: pause switches per book, Skip for now, Remove, revision scheduling, design problems, and the progress bar rules (only wrong answers lower it).

## 4. How it works

### 4.1 Where lessons come from

- A lesson is built **from the section's page text and its existing grounded points**. Because revision later asks about those same points, what the lesson teaches and what revision asks always match.
- **Every part of the lesson cites its page(s).**
- Each lesson ends with a short **"Left out"** line naming what was dropped, e.g. "Left out: the Memcached code snippet, the repeated intro". This is how omissions stay visible.
- **How strictly the text is checked** *(my call)*:
  - Every "What to remember" item carries an evidence quote and goes through the same mechanical check as points: the quote must appear in the section's page text, and the cited pages must fall within the section. Items that fail are dropped.
  - The narrative parts ("The idea", "How it works") must cite pages inside the section's range. They aren't quote-checked, since a quote per sentence would roughly double the output under the token budget.
  - The facts that revision tests are therefore always mechanically grounded, and the prose is prompt-grounded, like grading.
- **Completeness wins over length** (from the user's goal "know everything in the book"):
  - Everything on the "kept" list stays, even if a lesson runs past 10 minutes. The minutes shown are an estimate, not a cap.
  - Lessons stay skimmable through their fixed structure.
  - One lesson per map item.

### 4.2 Coverage check (part of "know everything")

After a lesson is generated, a **second AI pass** compares the section's pages with the lesson and its points, and lists important ideas that are missing. Missing ideas are:

- added to the lesson, and
- added as new grounded points with questions, so they also enter revision. They go through the same evidence-quote and page checks as ingestion (`Grounding`).

This also improves coverage for sections whose original point extraction was thin. *(Builder call: one coverage pass per lesson, no loop.)*

**New points found in a section already learned** *(my call)*. This applies to lessons opened for sections learned before lessons existed, or reached via "Builds on":
- The new points are added, counted in progress, and scheduled due now, the same as `Scheduler.onLearned`.
- The lesson shows "N new points found in this section, now in your revision", so a dip in the percentage or a lost Mastered is explained, not silent.
- This is an honest change: the syllabus grew, and nothing was answered wrong. It is consistent with progress counting known-well points out of all points.

### 4.3 Lesson content

- **Text**, in short sections:
  - "The idea": what it is and why it exists.
  - "How it works": the mechanism, with the one example.
  - "What to remember": the trade-offs, numbers and gotchas, each with its page.
- **Diagrams** *(my call)*:
  - The AI writes zero to two diagrams as **Mermaid** text, which the browser renders.
  - A diagram is only included when it shows structure or flow better than words.
  - A diagram that fails to render is dropped silently rather than shown broken.
- **YouTube reference** *(my call)*: the AI suggests search terms, and the lesson shows a **YouTube search link** built from them (`https://www.youtube.com/results?search_query=…`). It does not link a specific video, because the app can't judge videos and has no YouTube API.
- **Connections ("Builds on")**:
  - Up to 3 sections the user has **already learned**, in any book, each with one sentence on how it relates.
  - Each one links to that section's lesson.
  - The AI picks them from the list of learned sections and their point summaries.
  - None are shown if nothing relevant has been learned yet.
- **"Read the full version: pp. x–y"** opens the page reference, as Up next shows today.

### 4.4 Quick check and finishing a section

- The lesson ends with a **quick check** of 2–3 questions taken from the section's points, asked and graded exactly like revision questions.
- **Mini design** (decided with the user):
  - For any section that teaches how a system is structured, one quick-check item is a small design task on the existing canvas. This is decided per lesson by the AI and is not limited to DESIGN chapters; Alex Xu's Chapter 1 qualifies.
  - The canvas **always starts blank**. The task may mention an earlier topic ("design it with a cache, building on what you learned about replication"), but the user redraws from scratch. No drawings are saved or reloaded.
  - It is scoped to the section's points and graded by the existing design grader against them.
- **Finishing the quick check marks the section learned.** This replaces pressing **Learned it**, and the answers count as the first revision attempt for those points: correct answers move them up a box, wrong ones come back soon.
  - The section's other points, the ones not asked in the check, are scheduled due now, as with Learned it today.
- **On a lesson for a section that's already learned** *(my call)*, the quick check is offered as optional practice. Answers there are real answers, so they count as revision attempts.
- *(My call:)* a smaller **"Mark learned without the check"** link stays for sections the user already knows or read in the book. It behaves exactly like today's Learned it.
- **Skip for now** and **Remove** stay available from the lesson and from Up next.

### 4.5 When lessons are made *(my call)*

- A lesson is generated **the first time its section is opened**, then saved and reused.
- To avoid waiting, the app also **prepares the next 2 Up next sections in the background** whenever a lesson is opened.
- While a lesson is still being prepared, the page shows "Preparing your lesson…" and opens it when ready. If the AI is unreachable, it says so and offers the page reference instead.
- For **existing books** this means no reprocessing: lessons appear as sections are opened. New books work the same way, so adding a book doesn't take longer.
- A lesson can be **regenerated** ("Rewrite this lesson") if it reads badly. The previous one is replaced.

### 4.6 Mastered

- A book shows **Mastered** when it has at least one counted point and every counted point is known well.
- Like the progress bar, this is honest: a wrong answer that drops a point removes Mastered until it's known well again.
- Home and Library show it per book.

### 4.7 Screens (rough)

The sketch reviewed in brainstorming is `.superpowers/brainstorm/…/content/lesson.html`.

- **Lesson page.** Reached by clicking an Up next item, or from a "Builds on" link. It shows:
  - the header (section, book, chapter, pages, estimated minutes)
  - the Builds on panel
  - the lesson text and diagrams
  - the Left out line
  - the YouTube and full-version links
  - the quick check
  - Skip for now and Remove
- **Home.** Up next items open their lesson; the current item's main button becomes **Start lesson**. Each book shows Mastered when earned.
- **Library.** Each book shows Mastered when earned.

## 5. Later (don't design out)

- Section boundaries that follow the book's own headings (font sizes or sub-bookmarks) instead of AI-guessed splits.
- Looser text matching in the grounding check, so fewer valid points are dropped over hyphenation or ligatures.
- An "extra practice" mode in revision when nothing is due.

## 6. Left to the builder

- Storage shape for lessons: a lesson entity per map item, holding the content as JSON or Markdown plus diagram text.
- Prompt wording, and the exact JSON contract for lesson, coverage and connection replies.
- How to split a long section into budget-sized calls and merge the result into one lesson.
- The Mermaid version and how it's loaded: an npm package bundled by Vite, not a CDN, since the app runs offline apart from Groq.
- How the background preparation is queued alongside ingestion (the single ingestion thread versus a separate one).
- Lesson page styling, within the existing look.
- How "estimated minutes" is computed.

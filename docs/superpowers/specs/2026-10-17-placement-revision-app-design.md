# Placement Revision App — Design

Date: 2026-10-17
Status: ready for approval

## 1. Intent

A personal study app for one person preparing for campus placement interviews. They learn from books on their own and use this app to (a) know exactly what to learn next and (b) never forget what they've already learned.

The idea comes from two habits that worked for them:

- **LeetCode Daily.** Once you've covered every DSA topic, a random daily problem keeps everything sharp because any topic can come up. They want that for system design and the rest of the core interview subjects.
- **JEE revision notes.** They kept a ritual list of constants, facts and gotchas, things you can't derive in the exam hall, and went through it regularly.

The core goal is **completeness**, which has two parts:

1. **Coverage.** Nothing in the syllabus is left out.
2. **Retention.** Whatever they've learned, they still know well.

**The syllabus is the material they upload, and nothing beyond it.** The app does not teach. It acts as a map (what to learn next, and exactly where in the book) and an examiner (revision questions and hands-on problems, checked against the book).

## 2. Goals, non-goals, anti-goals

### Goals (v1)

- Ingest an uploaded PDF book and break it into a learning map plus individual knowledge points, each tied to its source pages.
- Show a continuous **Up next** queue of things to learn, each with exact page references. The user works at their own pace.
- **Revision on demand.** Questions come only from material the user has marked as learned. Every learned point keeps coming back until it's solid, and comes back again before it fades.
- **Hands-on system design problems** on a drag-and-drop canvas, reviewed by AI against the book's design.
- **One honest main progress bar**: the share of the syllabus currently known well. It goes down when the user forgets something.
- The first syllabus is **System Design Interview (Alex Xu)**, which is already in the project folder.

### Non-goals (v1)

- Teaching content or explaining topics from scratch. The user learns from the book.
- Going beyond the uploaded material, e.g. "you should also study X".
- Phone or mobile use, hosting, login, or multiple users.
- Other hands-on problem types: Java coding, SQL, OOP/low-level design. These are planned for later, Java first.
- Non-PDF resources such as links and pasted text. *(Builder call: deferred; all current material is PDF.)*

### Anti-goals

These would make the app a failure even if it technically works. The user said they'd quietly stop using it if it confused them, wasted their time, or didn't make them feel they were gaining anything.

- **Confusing.** Questions or feedback that are wrong, contradict the book, nitpick wording, or invent requirements the book doesn't have. Every question, model answer and piece of feedback must be grounded in the book and cite the page. If the book doesn't clearly settle something, the app says so instead of making it up.
- **Wasting time.** Setup friction on each visit, near-duplicate questions, trivia nobody asks in interviews, or questions about filler such as the preface, "subscribe to our list" pages, or the closing chapter. Opening the app should land the user straight where they left off. They can stop a revision session at any moment without losing anything.
- **No felt gain.** A progress bar that only ever goes up, or that stalls with no explanation. Progress has to be honest and visible, including small wins such as "6 weak points fixed this week".

## 3. Constraints (decided by the user)

- **Runs on the user's laptop only** (Linux). Single user, with no auth and no hosting.
- **Frontend: React. Backend: Java with Spring Boot.** Part of the motivation is that both are on the user's interview list.
- **AI: Groq.** There are 13 keys in `.env` (`GROQ_API_KEY`, `GROQ_API_KEY2` … `GROQ_API_KEY13`), and the user confirmed all are legitimate and should be used. Rotate across them to spread rate limits. `HF_TOKEN` is also present; there is no stated use for it.
- **Java 23 and Node 22 are installed.** Maven and Gradle are not; use a wrapper.
- Books live in the project root as PDFs. All six have extractable text, checked with `pdftotext`.

## 4. How it works

### 4.1 Concepts

- **Resource**: an uploaded book.
- **Map item**: one chunk the user goes and learns, typically a section of a few pages, with a title and a page range. *(Builder call on exact granularity: something learnable in roughly 20–40 minutes.)*
- **Point**: one atomic thing worth knowing, such as a fact, concept, trade-off, gotcha or number, with its source page(s). Points belong to map items. They are the unit that progress counts and revision schedules.
- **Problem**: a hands-on system design exercise derived from a design chapter.

### 4.2 Ingestion

1. The user adds a PDF through the UI. The six books already in the project folder should be easy to add, at minimum the Alex Xu book.
2. The app extracts text per page, keeping page numbers, and detects the chapter and section structure.
3. It skips filler: front matter, the table of contents, mailing-list or promo pages, indexes, and closing "keep learning" chapters (for Alex Xu, chapter 16).
4. For each section, the AI extracts points with page references and writes questions and model answers grounded in that text.
5. For design chapters (Alex Xu chapters 4–15), it also builds a problem: the requirements as the book states them, and the book's reference design, as a list of components and key decisions mapped to points.
6. Ingestion runs in the background, survives restarts and rate limits (it resumes where it stopped), and shows progress. A large book being slow is acceptable.

### 4.3 The map ("Up next")

- A single continuous queue rather than a daily portion. The user may get through 2 items or 10 in a day.
- **Within a book: strictly sequential** in the book's order.
- **Across books: rotate.** The queue switches between books so the user isn't stuck on one subject. *(Builder call: switch books at chapter boundaries.)* With only one book loaded, the queue simply follows that book.
- Each item shows its title, the book, and the exact page range, plus a **Learned it** button.
- Marking an item learned makes its points eligible for revision. It does **not** raise the progress bar on its own.
- **The user starts from the beginning of the book.** They haven't started Alex Xu, so v1 does not need a way to mark many items learned at once.

### 4.4 Revision

- **Started by the user** with a button. It never interrupts.
- Only points from map items marked learned are included.
- **Scheduling is spaced repetition**: new and weak points come up soon, and solid points come back at growing intervals before they would fade. Every learned point must eventually be asked, so nothing gets quietly skipped. *(Builder call on algorithm, e.g. SM-2 or FSRS.)*
- **Question types:**
  - **Short answer.** The user types an answer, the AI judges it against the source text and the model answer, and gives feedback ("right", "partly right — you missed X", "wrong") with the page reference.
  - **Quick-fire numbers.** Must-memorise values, the JEE-constants equivalent. In Alex Xu this is mainly chapter 2: powers of two, latency numbers, and availability nines. These should come up more often and get fast, exact checking.
  - **Design problems** (see 4.5).
- Questions about the same point should vary over time, and the user should not see near-duplicates in a session.
- The user can stop at any time and loses nothing.

### 4.5 Hands-on system design (v1's hands-on type)

- After a design chapter is marked learned, its problem can appear in revision.
- The screen shows the problem's requirements, a component palette (client, load balancer, web server, cache, database, queue, CDN, ID generator, plus custom boxes with a label), a canvas for placing and connecting boxes, and a notes box for reasoning such as "302 not 301 because…".
- **Submit** sends the design to the AI as **structured text** (nodes, edges, labels, notes), not an image, because Groq models are text-only. The AI compares it with the book's reference design and returns what was covered, what was missed, and where to reread, with page references.
- Each point the problem involves is marked right or wrong, so a weak design lowers progress the same way a wrong answer does.
- A design that's valid but different from the book's should not be marked wrong just for being different. The feedback should say how it differs from the book's approach and what that trade-off costs, without inventing a "correct" answer the book doesn't give.

### 4.6 Progress

- **Main bar**: known-well points ÷ total points across all loaded books.
- A point is **known well** once it has been answered correctly in revision and hasn't been missed since. Answering it wrong removes it, so the bar can go down.
- **Being overdue does not lower the bar.** Forgetting is detected by a wrong answer, not by time passing. Overdue points are shown as "due for revision" instead. *(Builder call: this keeps the bar from falling just because the user had a busy week, which would feel like "no gain".)*
- **No deadline or daily time budget.** The user said time doesn't matter and completion does. The scheduler is open-ended, with no target date and no daily quota. Point granularity is about what makes a clean question (one idea per point), not about volume.
- **Per-book bars** under the main one.
- **Visible wins**, e.g. "6 weak points fixed this week" or "chapter 4 fully solid".

### 4.7 Screens (rough)

Rough mockups were reviewed in the brainstorming session, in `.superpowers/brainstorm/…/content/` (`session-flow-v2.html` and `sd-canvas.html`).

- **Home**: main progress bar, per-book bars, the Up next queue with the current item highlighted, and a Revise button showing how many points are due. Opening the app lands here, ready to continue.
- **Revision**: one question at a time, an answer box, Check, and feedback with a page reference.
- **Design problem**: requirements, palette, canvas, notes, Submit, and feedback.
- **Library**: uploaded books with ingestion status, and Add resource.

### 4.8 When the AI gets it wrong *(builder call, guarding the "confusing" anti-goal)*

- **"I was actually right"** overrules a grade, and the override counts as correct for progress.
- **Flag a question** to retire it permanently. Its point gets a different question next time.
- **Hide a map item** that shouldn't be in the syllabus, such as misdetected filler. Its points leave the total. Hidden items can be restored from the Library.
- There is no mandatory review screen after ingestion. The user fixes things as they come up.

### 4.9 Code style *(builder call)*

Plain, idiomatic Spring Boot and React. Avoid "magic" libraries where a standard approach works. The user may read and extend the code, and building it is part of their interview preparation. Keep the problem-type code easy to extend, since Java coding problems come next.

## 5. Later (not v1, but don't design it out)

- Add the other books already in the folder: Designing Data-Intensive Applications, Web Scalability for Startup Engineers, Operating Systems: Three Easy Pieces, Head First Design Patterns, and The Pragmatic Programmer. This turns on cross-book rotation.
- **Java hands-on coding problems** that run on the local JDK against test cases. These come next once the user adds Java source material covering constructors, generics and wildcards, and Spring Boot. React, DBMS/SQL, Networks and OOP design come after that.
- Links and pasted text as resources.

## 6. Left to the builder

The database and schema (local and file-based), PDF and text extraction libraries, canvas library, which Groq models to use for which jobs, prompt design, key-rotation strategy, spaced-repetition algorithm, map-item granularity, UI styling, and project layout. The builder should also choose how to cite pages: printed page numbers versus PDF page indexes, whichever the user can actually find in their PDF reader.

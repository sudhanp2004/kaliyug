# Placement Revision App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a laptop-only study app. It turns an uploaded PDF book into a sequential "Up next" learning map and a spaced-repetition revision loop, with short-answer, quick-fire and system-design-canvas problems that AI grades against the book. v1 uses Alex Xu's *System Design Interview*.

**Architecture:** A Spring Boot backend with Spring Data JPA on a file-based H2 database. It extracts PDF text with PDFBox, finds chapters from the PDF's bookmarks, and calls Groq (OpenAI-compatible API, rotating across 13 keys) to split chapters into sections, extract grounded points and questions, and grade answers. A Vite + React + TypeScript frontend, built into `frontend/dist`, is served by Spring Boot on `http://localhost:8080`. One `./run.sh` starts everything.

**Tech Stack:** Java 21 target (JDK 23 installed), Spring Boot (start.spring.io's current default), Spring Web, Spring Data JPA, H2, Apache PDFBox 3.x, Jackson, JUnit 5, MockRestServiceServer. React 18+, TypeScript, Vite, react-router-dom, `@xyflow/react` (canvas), Vitest, and React Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-17-placement-revision-app-design.md`

## Global Constraints

- Runs on the user's laptop only. Single user, no auth, no hosting. The server binds to `localhost:8080`.
- Frontend: React. Backend: Java with Spring Boot. Plain, idiomatic Spring and React, with no "magic" libraries where a standard approach works.
- AI: Groq, `https://api.groq.com/openai/v1/chat/completions`. Default model: `openai/gpt-oss-120b`, set in `app.groq.model` (verified available on 2026-10-17).
- Keys: every variable in `/home/sudhan/cibiz/kaliyug/.env` matching `^GROQ_API_KEY\d*$` (13 keys), all used in rotation. Keys are never logged, returned by an API, or committed.
- `.env`, `data/`, `*.pdf`, `node_modules/`, `target/`, `frontend/dist/` and `.superpowers/` are git-ignored. The books are large and copyrighted, so they are never committed.
- Page citations use **PDF page numbers (1-based)**, which is what a PDF reader's page box shows. This book has no reliable printed page numbers.
- Every question, model answer and piece of feedback must be grounded in the book text and show its page(s). If the book doesn't settle something, the app says so.
- Overdue points do **not** lower progress. Only a WRONG or PARTIAL verdict removes "known well".
- Within a book the map is strictly sequential. Across books, the map rotates at chapter boundaries.
- v1 accepts only PDFs. A PDF without bookmarks fails ingestion with a clear message.
- Base Java package: `com.kaliyug`. Backend lives in `backend/`, frontend in `frontend/`, runtime data in `data/` (H2 database and copied books).

## Review Focus

1. **AI output that is invalid or ungrounded.** This includes malformed JSON, pages outside the item's range, and evidence quotes that aren't in the book. Such output is retried once, then dropped, and never shown to the user. *(Task 5: `dropsPointsWithOutOfRangePagesOrFakeEvidence`, `invalidSectionSplitFallsBackToWholeChapter`)*
2. **Laptop closed, app restarted, or all keys rate-limited in the middle of ingestion.** Ingestion resumes where it stopped with no duplicate points, and a burst of 429s waits instead of failing the book. *(Task 2: `allKeysCoolingWaitsForEarliest`; Task 5: `rerunAfterInterruptionCreatesNoDuplicates`)*
3. **Groq unreachable while grading.** The user sees "AI is unreachable right now — your answer wasn't graded, try again", their typed answer stays in the box, and the point's schedule is unchanged. *(Task 8: `gradingOutageReturns503AndLeavesPointUntouched`; Task 12: `keepsAnswerWhenGradingFails`)*
4. **Blank answer or empty canvas.** A blank answer is graded WRONG locally, with no AI call, and the model answer is shown. An empty canvas is rejected with "Add at least one box before submitting." *(Task 8: `blankAnswerIsWrongWithoutCallingAi`; Task 9: `emptyCanvasIsRejected`)*
5. **Importing a PDF that has no bookmarks** (*Web Scalability for Startup Engineers* in the folder is one). The resource shows FAILED with "This PDF has no bookmarks (table of contents), so chapters can't be detected yet." and the app keeps working. *(Task 4: `pdfWithoutOutlineIsRejected`; Task 10: `importOfPdfWithoutBookmarksShowsFailedReason`)*

---

## File Structure

```
kaliyug/
  .gitignore
  run.sh                                   # builds frontend if needed, starts backend, opens browser
  backend/                                 # Spring Boot (Maven wrapper)
    pom.xml
    src/main/resources/application.properties
    src/main/resources/prompts/            # one .txt per AI job (system prompts)
      classify-chapters.txt  split-sections.txt  extract-points.txt
      generate-problem.txt   grade-answer.txt    grade-design.txt   new-question.txt
    src/main/java/com/kaliyug/
      KaliyugApplication.java
      web/SpaForwardController.java        # non-/api GETs -> index.html
      web/HealthController.java
      web/ApiExceptionHandler.java         # LlmUnavailableException -> 503, IllegalArgumentException -> 400, DuplicateResourceException -> 409
      config/DotEnv.java                   # parses .env file
      config/AppConfig.java                # Clock bean, ingestion executor, KeyPool bean
      llm/LlmClient.java                   # interface
      llm/GroqClient.java                  # implements LlmClient over RestClient
      llm/KeyPool.java                     # round-robin + cooldown
      llm/JsonLlm.java                     # ask(system, user, Class<T>) with one retry
      llm/LlmUnavailableException.java
      llm/Prompts.java                     # loads prompts/*.txt
      domain/  Resource, ResourceStatus, PageText, Chapter, ChapterKind, MapItem,
               Point, PointKind, Question, QuestionType, Attempt, Verdict, Problem
      domain/  *Repository.java            # one Spring Data repo per entity
      ingest/PdfBook.java                  # pages + outline via PDFBox
      ingest/OutlineChapter.java           # record(title, startPage, endPage)
      ingest/AiContracts.java              # records for AI JSON (see Task 5)
      ingest/Grounding.java                # evidence / page validation
      ingest/IngestionService.java         # pipeline, resumable
      ingest/IngestionResumer.java         # ApplicationReadyEvent -> resume
      library/LibraryService.java  library/LibraryController.java  library/DuplicateResourceException.java
      map/QueueService.java  map/MapController.java
      revision/Scheduler.java  revision/RevisionService.java  revision/AnswerGrader.java  revision/RevisionController.java
      design/DesignService.java  design/DesignController.java
      progress/ProgressService.java  progress/ProgressController.java
    src/test/java/com/kaliyug/...          # mirrors main; FakeLlm in llm/FakeLlm.java
  frontend/                                # Vite React TS
    vite.config.ts                         # /api proxy -> 8080 for dev; vitest config
    src/main.tsx  src/App.tsx  src/api.ts
    src/pages/HomePage.tsx  RevisionPage.tsx  LibraryPage.tsx
    src/components/ProgressBar.tsx  QueueList.tsx  QuestionCard.tsx  FeedbackCard.tsx  DesignProblem.tsx
    src/**/*.test.tsx
```

---

### Task 1: Scaffold, git, and single-command run

**Files:**
- Create: `.gitignore`, `run.sh`, `backend/` (from start.spring.io), `backend/src/main/java/com/kaliyug/web/HealthController.java`, `backend/src/main/java/com/kaliyug/web/SpaForwardController.java`, `frontend/` (from Vite)
- Test: `backend/src/test/java/com/kaliyug/web/HealthControllerTest.java`

**Interfaces:**
- Produces: `GET /api/health` → `{"status":"ok"}`. Spring serves `frontend/dist` at `/`, and `GET /revise` and `GET /library` forward to `/index.html`.

- [ ] **Step 1: Init git and ignore file**

```bash
cd /home/sudhan/cibiz/kaliyug && git init
printf '.env\ndata/\n*.pdf\nnode_modules/\ntarget/\nfrontend/dist/\n.superpowers/\n' > .gitignore
git status --short   # must NOT list .env or any .pdf
```

- [ ] **Step 2: Generate backend**

```bash
cd /home/sudhan/cibiz/kaliyug && curl -s https://start.spring.io/starter.zip \
  -d type=maven-project -d language=java -d javaVersion=21 \
  -d groupId=com.kaliyug -d artifactId=kaliyug -d name=kaliyug -d packageName=com.kaliyug \
  -d dependencies=web,data-jpa,h2,validation -o /tmp/kaliyug-backend.zip \
  && mkdir backend && unzip -q /tmp/kaliyug-backend.zip -d backend && rm /tmp/kaliyug-backend.zip
```
Add `org.apache.pdfbox:pdfbox` (latest 3.x) to `backend/pom.xml`. Rename the generated main class to `KaliyugApplication` if it isn't already.

- [ ] **Step 3: `application.properties`**

```properties
server.address=127.0.0.1
server.port=8080
app.data-dir=../data
app.books-dir=..
app.env-file=../.env
app.groq.base-url=https://api.groq.com/openai/v1
app.groq.model=openai/gpt-oss-120b
spring.datasource.url=jdbc:h2:file:${app.data-dir}/kaliyug;AUTO_SERVER=FALSE
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
spring.web.resources.static-locations=file:../frontend/dist/
```
Tests use `src/test/resources/application.properties` with `spring.datasource.url=jdbc:h2:mem:test`, `app.env-file=src/test/resources/test.env` (containing `GROQ_API_KEY=k1` and `GROQ_API_KEY2=k2`), and `app.data-dir=target/test-data`.

- [ ] **Step 4: Write the failing test** `HealthControllerTest`: `@SpringBootTest @AutoConfigureMockMvc`. `GET /api/health` returns 200 with JSON `$.status == "ok"`. `GET /revise` is forwarded to `/index.html` (`forwardedUrl("/index.html")`).

- [ ] **Step 5: Run it and confirm it fails**
Run: `cd backend && ./mvnw -q test -Dtest=HealthControllerTest`
Expected: FAIL with 404s.

- [ ] **Step 6: Implement** `HealthController` and `SpaForwardController`. The latter is a `@Controller` with `@GetMapping({"/revise", "/library"})` returning `"forward:/index.html"`. Use explicit routes, because Spring's path parser rejects `/**/{x}` patterns. `/` is served as `index.html` by the static handler.

- [ ] **Step 7: Run it and confirm it passes**
Run: `cd backend && ./mvnw -q test -Dtest=HealthControllerTest`
Expected: PASS

- [ ] **Step 8: Scaffold the frontend**

```bash
cd /home/sudhan/cibiz/kaliyug && npm create vite@latest frontend -- --template react-ts \
  && cd frontend && npm install && npm install react-router-dom @xyflow/react \
  && npm install -D vitest @testing-library/react @testing-library/jest-dom @testing-library/user-event jsdom
```
In `vite.config.ts`, proxy `/api` to `http://localhost:8080` and set `test: { environment: 'jsdom', setupFiles: './src/setupTests.ts' }`. `setupTests.ts` imports `@testing-library/jest-dom/vitest`. Add `"test": "vitest run"` to `package.json`.

- [ ] **Step 9: `run.sh`** (make it executable). Steps: run `npm run build` in `frontend/` if `frontend/dist` is missing or any file in `frontend/src` is newer than `frontend/dist/index.html`. Then `mkdir -p data`, start `./mvnw spring-boot:run` in `backend/` in the background, poll `http://localhost:8080/api/health` until it returns 200, run `xdg-open http://localhost:8080`, and `wait` on the backend.

- [ ] **Step 10: Verify**
Run: `./run.sh`
Expected: the browser opens the Vite starter page at `localhost:8080`. Stop with Ctrl-C.

- [ ] **Step 11: Commit**

```bash
git add .gitignore run.sh backend frontend docs && git commit -m "chore: scaffold Spring Boot backend, Vite React frontend, run script"
```

---

### Task 2: Groq client with key rotation

**Files:**
- Create: `config/DotEnv.java`, `config/AppConfig.java`, `llm/LlmClient.java`, `llm/GroqClient.java`, `llm/KeyPool.java`, `llm/JsonLlm.java`, `llm/LlmUnavailableException.java`, `llm/Prompts.java`
- Test: `config/DotEnvTest.java`, `llm/KeyPoolTest.java`, `llm/GroqClientTest.java`, `llm/JsonLlmTest.java`, plus test helper `llm/FakeLlm.java`

**Interfaces:**
- Produces:
  - `static Map<String,String> DotEnv.parse(Path file)`: `KEY=value` lines. Ignores blanks and `#` comments, strips surrounding single or double quotes, and the last duplicate wins.
  - `KeyPool(List<String> keys, Clock clock)`, with `String acquire() throws InterruptedException` (round-robin over keys not cooling, blocking until the earliest cooldown ends when all are cooling), `void coolDown(String key, Duration d)`, and `int size()`.
  - `interface LlmClient { String completeJson(String system, String user); }`: returns the assistant message content (a JSON string). Throws `LlmUnavailableException` (unchecked) when it can't get an answer.
  - `GroqClient implements LlmClient`, built from `RestClient.Builder`, `KeyPool`, `@Value app.groq.base-url`, and `@Value app.groq.model`. POST `/chat/completions` with `{model, messages:[{role:system},{role:user}], response_format:{type:"json_object"}, temperature:0.2}`.
  - `JsonLlm(LlmClient, ObjectMapper)`, with `<T> T ask(String system, String user, Class<T> type)`. On a parse failure, it calls once more with the user message plus `"\n\nYour previous reply was not valid JSON for the required shape: <error message>. Reply with JSON only."`. If that fails too, it throws `LlmUnavailableException`.
  - `Prompts`, with `String get(String name)`: loads `classpath:prompts/<name>.txt` and caches it.
  - `AppConfig` beans: `Clock` (`Clock.systemUTC()`), `KeyPool` (from `DotEnv.parse(app.env-file)`, keys whose names match `^GROQ_API_KEY\d*$` sorted by numeric suffix, failing startup with "No GROQ_API_KEY found in <path>" if there are none), `ExecutorService ingestionExecutor` (single thread, daemon), `JsonLlm`.

- [ ] **Step 1: Write failing tests**

`DotEnvTest.parsesKeysCommentsAndQuotes`: a file containing `# c\nGROQ_API_KEY=a\nGROQ_API_KEY2="b"\n\nHF_TOKEN='c'` parses to `{GROQ_API_KEY=a, GROQ_API_KEY2=b, HF_TOKEN=c}`.

`KeyPoolTest` (use a mutable test clock):
```java
@Test void rotatesRoundRobin() { var p = new KeyPool(List.of("a","b","c"), clock);
  assertEquals(List.of("a","b","c","a"), List.of(p.acquire(), p.acquire(), p.acquire(), p.acquire())); }
@Test void skipsCoolingKey() { p.coolDown("b", Duration.ofSeconds(30));
  assertEquals(List.of("a","c","a"), List.of(p.acquire(), p.acquire(), p.acquire())); }
@Test void allKeysCoolingWaitsForEarliest() // keys a,b; a cools 50ms, b 5s (real clock): acquire() returns "a" after >= 40ms and < 2s
```

`GroqClientTest` (`MockRestServiceServer` bound to a `RestClient.Builder`):
- `sendsBearerKeyModelAndJsonMode`: the request has `Authorization: Bearer k1` and a body with `$.model == "openai/gpt-oss-120b"` and `$.response_format.type == "json_object"`. The reply `{"choices":[{"message":{"content":"{\"x\":1}"}}]}` makes it return `{"x":1}`.
- `on429CoolsKeyAndRetriesWithNext`: the first request (key k1) gets 429 with `retry-after: 7`, and the second request carries `Bearer k2` and succeeds. Assert k1 is cooling for 7s (the pool's next `acquire()` returns k2).
- `serverErrorsRetryThreeTimesThenThrow`: four 500s cause `LlmUnavailableException`. Inject the backoff as `Duration` via a package-private constructor so the test uses 1ms.

`JsonLlmTest`: with `FakeLlm` returning `"not json"` then `{"a":"x"}`, `ask(..., Rec.class)` returns `Rec("x")`, and the second user message contains `"was not valid JSON"`. If it returns non-JSON twice, it throws `LlmUnavailableException`.

`FakeLlm implements LlmClient`: a queue of canned replies or `RuntimeException`s, keyed in call order, that records `(system, user)` calls in a public `List<String[]> calls`. Later tasks use it everywhere.

- [ ] **Step 2: Run and confirm they fail**
Run: `cd backend && ./mvnw -q test -Dtest='DotEnvTest,KeyPoolTest,GroqClientTest,JsonLlmTest'`
Expected: compilation failure / FAIL

- [ ] **Step 3: Implement the classes listed in Interfaces.** `GroqClient` retries on 429 without limit, rotating keys (the cooldown is `retry-after` seconds, or 60s if the header is missing). It retries 5xx and I/O errors 3 times with 2s/4s/8s backoff before throwing `LlmUnavailableException`. It never puts the key in an exception message or log.

- [ ] **Step 4: Run and confirm they pass**
Run: same command
Expected: PASS

- [ ] **Step 5: Real-key smoke check**
Write a JUnit test `GroqLiveTest` tagged `@Tag("live")`. Exclude it by default: add the pom property `<test.excluded>live</test.excluded>` and the surefire config `<excludedGroups>${test.excluded}</excludedGroups>`. The test builds a `GroqClient` from the real `../.env` and calls `completeJson("Reply with JSON.", "Return {\"ok\":true}")`.
Run: `cd backend && ./mvnw -q test -Dgroups=live -Dtest.excluded= -Dtest=GroqLiveTest`
Expected: PASS, with a reply containing `"ok"`.

- [ ] **Step 6: Commit** `git commit -m "feat: Groq client with rotating key pool and JSON retry"`

---

### Task 3: Domain model

**Files:**
- Create: `domain/*.java` (entities, enums, repositories)
- Test: `domain/DomainMappingTest.java`

**Interfaces:**
- Produces these JPA entities, each with `Long id` (`@GeneratedValue IDENTITY`), getters and setters, and a protected no-arg constructor. Each has a `JpaRepository<Entity, Long>` with the queries listed.
  - `Resource`: `title`, `storedPath`, `sha256` (unique), `ResourceStatus status {INGESTING, READY, FAILED}`, `failureReason`, `totalPages`, `createdAt`. Repo: `Optional<Resource> findBySha256(String)`, `List<Resource> findByStatus(ResourceStatus)`.
  - `PageText`: `resource`, `pageNumber`, `@Lob text`. Repo: `List<PageText> findByResourceIdAndPageNumberBetweenOrderByPageNumber(Long, int, int)`.
  - `Chapter`: `resource`, `orderIndex`, `title`, `startPage`, `endPage`, `ChapterKind kind {UNCLASSIFIED, CONCEPT, DESIGN, FILLER}`, `boolean sectionsSplit`. Repo: `List<Chapter> findByResourceIdOrderByOrderIndex(Long)`.
  - `MapItem`: `chapter`, `orderIndex`, `title`, `startPage`, `endPage`, `boolean ingested`, `boolean hidden`, `Instant learnedAt` (null means queued). Repo: `List<MapItem> findByChapterIdOrderByOrderIndex(Long)`, `List<MapItem> findByHiddenTrue()`.
  - `Point`: `mapItem`, `PointKind kind {FACT, CONCEPT, TRADEOFF, GOTCHA, NUMBER}`, `@Lob text`, `firstPage`, `lastPage`, `@Lob evidence`, `int box`, `Instant dueAt` (null until learned), `boolean knownWell`. Repo: `List<Point> findByMapItemId(Long)`, `void deleteByMapItemId(Long)`, and `@Query` `List<Point> findDue(Instant now)`. That query returns points whose `dueAt <= now`, whose map item is not hidden and is learned, and whose chapter kind ≠ FILLER, ordered by `dueAt, id`.
  - `Question`: `point`, `QuestionType type {SHORT, QUICKFIRE}`, `@Lob prompt`, `@Lob modelAnswer`, `boolean retired`, `Instant lastAskedAt`. Repo: `List<Question> findByPointIdAndRetiredFalseOrderByLastAskedAtAscIdAsc(Long)`. Nulls come first; order with `NULLS FIRST` via `@Query` if needed.
  - `Attempt`: `point`, `question` (nullable), `problem` (nullable), `Verdict verdict {CORRECT, PARTIAL, WRONG}`, `boolean overridden`, `int boxBefore`, `Instant dueBefore`, `boolean knownWellBefore`, `@Lob answer`, `Instant at`. Repo: `List<Attempt> findByAtAfterOrderByAt(Instant)`, `Optional<Attempt> findFirstByPointIdAndAtBeforeOrderByAtDesc(Long, Instant)`.
  - `Problem`: `chapter` (one-to-one), `title`, `@Lob requirementsJson`, `@Lob referenceJson`, `@ManyToMany Set<Point> points`, `int box`, `Instant dueAt` (null until the chapter is learned). Repo: `Optional<Problem> findByChapterId(Long)`, and `@Query` `List<Problem> findDue(Instant now)` (`dueAt <= now`, ordered by `dueAt`).

- [ ] **Step 1: Write failing test** `DomainMappingTest` (`@DataJpaTest`). Persist Resource, then Chapter (DESIGN), then MapItem (learned, not hidden), then Point (`dueAt` = now − 1 minute), then Question, plus a second Point on a hidden item. `pointRepository.findDue(now)` returns exactly the first point. A Problem linked to the chapter with both points round-trips, and `points.size() == 2`.

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=DomainMappingTest`. Expected: FAIL (compilation).
- [ ] **Step 3: Implement entities and repositories.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: domain entities and repositories"`

---

### Task 4: PDF reading and chapter detection

**Files:**
- Create: `ingest/PdfBook.java`, `ingest/OutlineChapter.java`
- Test: `ingest/PdfBookTest.java`

**Interfaces:**
- Produces:
  - `record OutlineChapter(String title, int startPage, int endPage)`
  - `PdfBook implements AutoCloseable`, with `static PdfBook open(Path)`, `int pageCount()`, `String pageText(int pageNumber)` (1-based, PDFBox `PDFTextStripper` with start and end page set), and `List<OutlineChapter> chapters()`.
  - `chapters()` uses the top-level outline items in document order. For each item, `startPage` is its destination's page index + 1. `endPage` is the next item's `startPage` − 1, or `pageCount()` for the last item. An item whose `endPage < startPage` gets `endPage = startPage`. Items without a resolvable page are skipped. If the outline is empty or missing, it throws `IllegalArgumentException("This PDF has no bookmarks (table of contents), so chapters can't be detected yet.")`.

- [ ] **Step 1: Write failing tests.** These use the real books in the project root, with `Assumptions.assumeTrue(Files.exists(...))` so they skip on machines without them.

```java
@Test void alexXuChapters() {
  try (var b = PdfBook.open(Path.of("../System Design Interview by Alex Xu.pdf"))) {
    assertEquals(269, b.pageCount());
    var ch = b.chapters();
    assertEquals(19, ch.size());
    assertEquals(new OutlineChapter("CHAPTER 8: DESIGN A URL SHORTENER", 119, 131), ch.get(9));
    assertEquals("AFTERWORD", ch.get(18).title()); assertEquals(269, ch.get(18).endPage());
    assertTrue(b.pageText(119).contains("URL SHORTENER"));
  } }
@Test void pdfWithoutOutlineIsRejected() {
  try (var b = PdfBook.open(Path.of("../Web Scalability For Startup Engineers PDF.pdf"))) {
    var e = assertThrows(IllegalArgumentException.class, b::chapters);
    assertTrue(e.getMessage().startsWith("This PDF has no bookmarks")); } }
```

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=PdfBookTest`. Expected: FAIL.
- [ ] **Step 3: Implement `PdfBook`.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: PDF text and bookmark-based chapter detection"`

---

### Task 5: Ingestion pipeline (grounded, resumable)

**Files:**
- Create: `ingest/AiContracts.java`, `ingest/Grounding.java`, `ingest/IngestionService.java`, `ingest/IngestionResumer.java`, `resources/prompts/{classify-chapters,split-sections,extract-points,generate-problem}.txt`
- Test: `ingest/GroundingTest.java`, `ingest/IngestionServiceTest.java`

**Interfaces:**
- Consumes: `JsonLlm`, `Prompts`, `PdfBook`, and the repositories from Task 3. The `ingestionExecutor` bean comes from Task 2.
- Produces:
  - `AiContracts` (nested records, Jackson-mapped; unknown properties ignored):
    - `ChapterKinds(List<ChapterKindItem> chapters)` / `ChapterKindItem(int index, ChapterKind kind)`
    - `Sections(List<Section> sections)` / `Section(String title, int startPage, int endPage)`
    - `ExtractedPoints(List<ExtractedPoint> points)` / `ExtractedPoint(PointKind kind, String text, int firstPage, int lastPage, String evidence, List<ExtractedQuestion> questions)` / `ExtractedQuestion(QuestionType type, String prompt, String modelAnswer)`
    - `ProblemSpec(String title, List<String> requirements, List<RefComponent> components, List<RefDecision> decisions)` / `RefComponent(String name, String role, List<Long> pointIds)` / `RefDecision(String decision, List<Long> pointIds)`
  - `Grounding`:
    - `static String normalize(String s)`: lowercase, every run of non-letters and non-digits becomes one space, trimmed.
    - `static boolean evidenceFound(String evidence, String pagesText)`: `normalize(evidence).length() >= 20` and `normalize(pagesText).contains(normalize(evidence))`.
    - `static boolean validSections(List<Section> s, int chapterStart, int chapterEnd)`: non-empty, `s[0].startPage == chapterStart`, `last.endPage == chapterEnd`, each `startPage <= endPage`, and each next start is in `{prev.endPage, prev.endPage + 1}`.
  - `IngestionService`:
    - `void start(Long resourceId)`: submits `run(resourceId)` to `ingestionExecutor`.
    - `void run(Long resourceId)`: synchronous, idempotent, and safe to call again after an interruption.
  - `run` steps, each committed in its own transaction so a crash loses at most one step:
    1. If there are no `PageText` rows yet, open the PDF and save every page's text. Save `Resource.totalPages`.
    2. If there are no `Chapter` rows yet, save `PdfBook.chapters()` as Chapters with `kind = UNCLASSIFIED`. An `IllegalArgumentException` sets `status = FAILED`, sets `failureReason = message`, and returns.
    3. If any chapter is UNCLASSIFIED, make one `classify-chapters` call. The user message lists `index | title | first 800 chars of the start page` for every chapter. Apply the kinds. Any index missing from the reply becomes CONCEPT.
    4. For each non-FILLER chapter in order, skip it if `sectionsSplit`. Otherwise make a `split-sections` call with the chapter text, where each page is preceded by `=== PAGE n ===`. If the result fails `validSections`, retry once with the reason appended to the message. If it still fails, use one section, `(chapter.title, startPage, endPage)`. Save the MapItems (`orderIndex` 0..n) and set `sectionsSplit = true`.
    5. For each MapItem with `!ingested`, in chapter order then item order, make an `extract-points` call with the item's page-marked text. In one transaction: `deleteByMapItemId`, then save each point that passes both checks: `firstPage` and `lastPage` within the item's range with `firstPage <= lastPage`, and `evidenceFound(evidence, item text)`. Each kept point gets its questions (questions with blank prompt or model answer are dropped, and a point with zero questions left is dropped). Set `ingested = true`. Log kept and dropped counts.
    6. For each DESIGN chapter whose items are all ingested and that has no Problem, make a `generate-problem` call. The user message has the chapter's points as `id | text | pages` plus the chapter text. Remove from each component's and decision's `pointIds` any ID that isn't one of this chapter's point IDs. Save the Problem with `points` = the union of the remaining IDs, `requirementsJson` / `referenceJson` serialized from the spec, `box = 0`, and `dueAt = null`.
    7. Set `status = READY`.
  - If any step throws `LlmUnavailableException`, sleep `retryDelay` (60s by default, 1ms in tests) and retry the same step, up to 5 times. After that, set `status = FAILED` with `failureReason = "AI service unreachable — press Retry in Library."`.
  - `IngestionResumer`: on `ApplicationReadyEvent`, calls `start(id)` for every resource with `status == INGESTING`.
- Prompt rules (write these into the prompt files):
  - Every prompt says: use only the provided book text, never outside knowledge, and reply with JSON only in the exact shape given.
  - `classify-chapters`: FILLER means title pages, forewords, acknowledgements, afterwords, "what's next" or "learning continues" chapters, indexes and promos. DESIGN means the chapter designs one specific system end to end. Everything else is CONCEPT.
  - `split-sections`: contiguous sections covering the whole chapter, each 2–8 pages, with titles taken from the book's own headings where possible.
  - `extract-points`: one idea per point, interview-relevant only, and no trivia about the book itself. NUMBER is for must-memorise values (powers of two, latencies, availability nines, capacity figures). `evidence` is a verbatim quote of at least one sentence from the text. Each point has 1–2 questions: SHORT for explanations and QUICKFIRE for NUMBER points, where the model answer is the bare value plus its unit. Model answers are drawn only from the text.
  - `generate-problem`: requirements exactly as the book states them, plus the components and key decisions of the book's design, each tied to point IDs from the list.

- [ ] **Step 1: Write failing tests**

`GroundingTest`:
```java
assertEquals("a b 1 2", Grounding.normalize("  A—b! 1,2 "));
assertTrue(Grounding.evidenceFound("the load  balancer evenly distributes", "...The load balancer evenly distributes incoming traffic..."));
assertFalse(Grounding.evidenceFound("too short", "too short text"));
assertFalse(Grounding.evidenceFound("caches always use write-through policy", "Cache uses read-through"));
assertTrue(Grounding.validSections(List.of(new Section("a",51,55), new Section("b",55,60), new Section("c",61,70)), 51, 70));
assertFalse(Grounding.validSections(List.of(new Section("a",51,55), new Section("b",57,70)), 51, 70));
```

`IngestionServiceTest` (`@SpringBootTest`, with a `FakeLlm` registered as a `@Primary LlmClient` bean). It uses a small fixture PDF, `src/test/resources/fixture.pdf`, built in a `@BeforeAll` with PDFBox: 6 pages, page text `"Page n. The load balancer evenly distributes incoming traffic across servers. Value is 42 ms."`, and outline entries `Foreword`→1, `Chapter 1: Basics`→2, and `Chapter 2: Design X`→4.
- `ingestsBookEndToEnd`: the fake replies are classify {0:FILLER, 1:CONCEPT, 2:DESIGN}, split ch1 → `[(Basics,2,3)]`, split ch2 → `[(Part A,4,5),(Part B,6,6)]`, then three extract replies each with one valid point, then a problem reply. Asserts: 3 map items, 3 points with questions, 1 problem whose `points` are the 2 ch2 points, status READY, and no PageText/extract calls for the Foreword pages.
- `dropsPointsWithOutOfRangePagesOrFakeEvidence`: an extract reply with three points, one valid, one with `firstPage` 9, and one with evidence `"servers are always stateless by design rule"`. Exactly 1 point is saved.
- `invalidSectionSplitFallsBackToWholeChapter`: both split replies are `[(x,2,2)]` for ch1 (2–3). One map item covering 2–3 is saved.
- `rerunAfterInterruptionCreatesNoDuplicates`: the FakeLlm throws `LlmUnavailableException` six times on the second extract call, so the resource becomes FAILED. Reset the status to INGESTING, call `run` again with fresh replies, and assert there are 3 points in total, no duplicates, and that classify and split were not called again (count `calls`).
- `designProblemDropsForeignPointIds`: the problem reply references a ch1 point ID and the ID 9999. Neither ends up in `problem.points`.

- [ ] **Step 2: Run** `./mvnw -q test -Dtest='GroundingTest,IngestionServiceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `AiContracts`, `Grounding`, the prompts, `IngestionService` (put `retryDelay` in a package-private setter used by tests), and `IngestionResumer`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: resumable grounded ingestion pipeline"`

---

### Task 6: Scheduler and progress

**Files:**
- Create: `revision/Scheduler.java`, `progress/ProgressService.java`, `progress/ProgressController.java`
- Test: `revision/SchedulerTest.java`, `progress/ProgressServiceTest.java`

**Interfaces:**
- Produces:
  - `Scheduler` (`@Component`, uses `Clock`). Intervals in days are `NORMAL = {0,1,3,7,14,30,60}` and `QUICKFIRE = {0,1,2,4,7,14,30}`. `NUMBER` points use QUICKFIRE; all others use NORMAL. Max box 6.
    - `void onLearned(Point p)`: `box = 0`, `dueAt = now`.
    - `void apply(Point p, Verdict v)`:
      - CORRECT: `box = min(box+1, 6)`, `dueAt = now + days[box]`, `knownWell = true`.
      - PARTIAL: `box = 1`, `dueAt = now + 1 day`, `knownWell = false`.
      - WRONG: `box = 0`, `dueAt = now + 10 minutes`, `knownWell = false`.
    - `void applyProblem(Problem pr, boolean allCovered)`: if all covered, `box = min(box+1,6)` and `dueAt = now + NORMAL[box]`. Otherwise `box = 1` and `dueAt = now + 1 day`.
  - `ProgressService`, with `ProgressDto progress()`. The records are:
    - `ProgressDto(Bar overall, List<BookBar> books, Wins wins, long dueCount, boolean preparing)`
    - `Bar(long knownWell, long total, int percent)`, where percent is `floor(100 * knownWell / total)` and 0 when total is 0
    - `BookBar(Long resourceId, String title, ResourceStatus status, String failureReason, Bar bar)`
    - `Wins(long weakPointsFixedThisWeek, List<String> solidChapters)`
  - What counts:
    - `total` covers points in non-hidden map items of non-FILLER chapters.
    - `knownWell` counts those points with `knownWell = true`.
    - `dueCount` is `pointRepository.findDue(now).size()`.
    - `preparing` is true if any resource is INGESTING.
    - `weakPointsFixedThisWeek` is the number of distinct points with a CORRECT attempt at or after now − 7 days whose immediately preceding attempt was WRONG or PARTIAL.
    - `solidChapters` lists the titles of chapters with at least one counted point where all counted points are known well.
  - `GET /api/progress` → `ProgressDto`.

- [ ] **Step 1: Write failing tests**

`SchedulerTest` (fixed clock `2026-10-17T00:00:00Z`):
```java
// box 2 FACT, CORRECT -> box 3, due 2026-10-24T00:00Z, knownWell true
// box 2 NUMBER, CORRECT -> box 3, due 2026-10-21T00:00Z
// box 6, CORRECT -> box 6, due +60d
// box 4 knownWell, PARTIAL -> box 1, due +1d, knownWell false
// box 4 knownWell, WRONG -> box 0, due 2026-10-17T00:10:00Z, knownWell false
// onLearned -> box 0, due == now
// applyProblem(box 0, true) -> box 1, due +1d ; applyProblem(box 3, false) -> box 1, due +1d
```

`ProgressServiceTest` (`@SpringBootTest @Transactional`). Fixture: 4 counted points (2 known well) plus 1 point in a hidden item and 1 in a FILLER chapter. Expect `overall == Bar(2, 4, 50)`. An overdue point with `knownWell = true` still counts as known well. With attempts point A WRONG on day −3 then CORRECT on day −1, and point B CORRECT only, `weakPointsFixedThisWeek == 1`. A chapter whose 2 points are both known well appears in `solidChapters`.

- [ ] **Step 2: Run** `./mvnw -q test -Dtest='SchedulerTest,ProgressServiceTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: Leitner scheduler and honest progress"`

---

### Task 7: Up-next queue, learned, hide

**Files:**
- Create: `map/QueueService.java`, `map/MapController.java`
- Test: `map/QueueServiceTest.java`, `map/MapControllerTest.java`

**Interfaces:**
- Consumes: `Scheduler.onLearned`, repositories.
- Produces:
  - `QueueService`, with `QueueDto queue(int limit)`. The records are `QueueDto(List<QueueItemDto> items, int remaining, boolean preparing)` and `QueueItemDto(Long id, String title, String bookTitle, String chapterTitle, int startPage, int endPage)`.
  - Queue algorithm (the rotation rule from the spec):
    1. For each resource, its **remaining list** is the non-hidden, unlearned map items of non-FILLER chapters, in chapter order then item order, cut off at the first item with `!ingested`.
    2. Order the resources:
       - first, any resource with a chapter that is partially learned (some items learned and some remaining);
       - then the rest by their latest `learnedAt` ascending, with resources never studied first;
       - ties broken by resource ID.
    3. Round-robin over that resource order, each turn taking the **whole next chapter block** (that chapter's remaining items) from the current resource. Skip resources that have run out. Stop when all are empty.
    4. Return the first `limit` items. `remaining` is the total across all resources. `preparing` is true when any resource is INGESTING.
  - `MapController`:
    - `GET /api/queue?limit=8` → `QueueDto`.
    - `POST /api/map-items/{id}/learned`: sets `learnedAt = now` and calls `onLearned` for each of its points. If every non-hidden item of the chapter is now learned and the chapter has a Problem with `dueAt == null`, sets `problem.dueAt = now + 1 day`. Returns 204. If the item is already learned, nothing changes (idempotent).
    - `POST /api/map-items/{id}/hide` and `/unhide` → 204.
    - `GET /api/map-items/hidden` → `List<QueueItemDto>`.

- [ ] **Step 1: Write failing tests**

`QueueServiceTest`:
- `singleBookIsSequential`: book A has ch1 [a1,a2] and ch2 [a3]. The queue is `[a1,a2,a3]`. After learning a1 it is `[a2,a3]`.
- `rotatesBetweenBooksAtChapterBoundaries`: A has ch1 [a1,a2] and ch2 [a3]; B has ch1 [b1] and ch2 [b2,b3]; neither has been studied. Order by ID (A first): `[a1,a2,b1,a3,b2,b3]`.
- `partiallyLearnedChapterGoesFirst`: B's ch2 has b2 learned. The queue starts with `b3`.
- `stopsAtFirstUningestedItem`: if A's a2 has `ingested = false`, A contributes only `[a1]`, `remaining` excludes a2 and a3, and `preparing` reflects the resource's status.
- `hiddenAndFillerExcluded`.

`MapControllerTest`:
- `learnedMakesPointsDueNow`
- `learnedLastItemOfDesignChapterSchedulesProblemTomorrow`
- `learnedTwiceIsIdempotent`
- `hideUnhideRoundTrip`

- [ ] **Step 2: Run** `./mvnw -q test -Dtest='QueueServiceTest,MapControllerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: rotating up-next queue with learned and hide"`

---

### Task 8: Revision — next question, grading, override, flag

**Files:**
- Create: `revision/AnswerGrader.java`, `revision/RevisionService.java`, `revision/RevisionController.java`, `web/ApiExceptionHandler.java`, `resources/prompts/{grade-answer,new-question}.txt`
- Test: `revision/AnswerGraderTest.java`, `revision/RevisionControllerTest.java`

**Interfaces:**
- Consumes: `Scheduler.apply`, `JsonLlm`, `PointRepository.findDue`, `ProblemRepository.findDue`.
- Produces:
  - `record AnswerGrade(Verdict verdict, String feedback, boolean notInBook)` (AI contract).
  - `AnswerGrader`, with `AnswerGrade grade(Question q, String answer)`:
    - A blank or whitespace answer gives `(WRONG, "No answer given.", false)` with no AI call.
    - QUICKFIRE: if `quickNormalize(answer).equals(quickNormalize(modelAnswer))`, return `(CORRECT, "Exactly right.", false)` with no AI call. `quickNormalize` lowercases and removes whitespace, commas, and the word "approximately" or "about".
    - Otherwise make a `grade-answer` call with the question, model answer, the point's evidence, the PDF text of the point's pages, and the user's answer.
    - Prompt rules: judge only against the provided book text, ignore wording and style, never require things the book doesn't say, PARTIAL means a correct core with a missed key element (and the feedback names it), and when the book doesn't settle something, set `notInBook = true` and say so in the feedback.
  - `RevisionService`:
    - `RevisionItemDto next(int answeredInSession, Set<Long> excludePointIds)` returns one of three things:
      - **Problem.** If a problem is due and either `answeredInSession >= 10` and `answeredInSession % 10 == 0`, or no due point remains after the exclusions, return `{type:"PROBLEM", problem:{id, title, requirements, chapterTitle}}` (the earliest-due problem).
      - **Question.** Otherwise take the first due point not in `excludePointIds`. Pick its first active question and set `lastAskedAt = now`. If it has no active question, make a `new-question` call (point text plus evidence pages, returning `{prompt, modelAnswer}`) and save the result with the type implied by the point (NUMBER → QUICKFIRE). If that call throws `LlmUnavailableException`, skip the point. Return `{type:"QUESTION", question:{id, type, prompt, pointId, bookTitle, chapterTitle}}`.
      - **Done.** If there is nothing left, return `{type:"DONE"}`.
    - `GradeResultDto answer(Long questionId, String answer)`: grades first. If the grader throws `LlmUnavailableException`, it propagates and nothing is saved. Otherwise it saves an Attempt with the point's before-state, calls `Scheduler.apply`, and returns `GradeResultDto(Long attemptId, Verdict verdict, String feedback, boolean notInBook, String modelAnswer, int firstPage, int lastPage, String bookTitle)`.
    - `void override(Long attemptId)`: if the attempt is already CORRECT or overridden, do nothing. Otherwise restore the point's `box`, `dueAt` and `knownWell` from the attempt's before-fields, apply CORRECT, and set `attempt.verdict = CORRECT` and `overridden = true`.
    - `void flag(Long questionId)`: sets `retired = true`.
  - `RevisionController`:
    - `GET /api/revision/next?answered=0&exclude=1,2` (`exclude` optional)
    - `POST /api/questions/{id}/answer` with body `{"answer":"..."}`
    - `POST /api/attempts/{id}/override` → 204
    - `POST /api/questions/{id}/flag` → 204
  - `ApiExceptionHandler` responses:
    - `LlmUnavailableException` → 503 `{"error":"AI is unreachable right now — your answer wasn't graded, try again."}`
    - `IllegalArgumentException` → 400 `{"error":<message>}`
    - Task 10 adds the 409 mapping.

- [ ] **Step 1: Write failing tests**

`AnswerGraderTest`:
- `blankAnswerIsWrongWithoutCallingAi` (FakeLlm `calls` is empty)
- `quickfireExactMatchSkipsAi`: model answer `"10 ms"`, answer `" 10ms "` → CORRECT
- `quickfireMismatchAsksAi`
- `shortAnswerUsesAiVerdict`: the fake returns `{"verdict":"PARTIAL","feedback":"Missed X","notInBook":false}`

`RevisionControllerTest` (`@SpringBootTest @AutoConfigureMockMvc`, FakeLlm, fixed `Clock` bean):
- `nextReturnsDueQuestionThenDone`: one due point with one question gives `type == "QUESTION"`. With `exclude=<thatPointId>` it gives `DONE`.
- `wrongAnswerDropsKnownWellAndReschedules`: the point is known well at box 3, and the fake grades WRONG. Afterwards `knownWell == false`, `box == 0`, and `dueAt == now + 10m`.
- `gradingOutageReturns503AndLeavesPointUntouched`: the fake throws `LlmUnavailableException`. Expect 503 with that exact error text, an unchanged point, and zero attempts.
- `overrideRestoresAndAppliesCorrect`: the point is at box 3 and gets graded WRONG. After override, `box == 4`, `knownWell == true`, and the attempt is overridden. A second override changes nothing.
- `flaggedQuestionIsNeverServedAndReplacementIsGenerated`: flag the only question. The next `next` call makes one `new-question` AI call and returns a question with a different ID.
- `problemServedEveryTenthAnswerOrWhenNoPointsLeft`: one problem is due and 20 points are due. `answered=10` returns PROBLEM, `answered=3` returns QUESTION, and `answered=3` with all points excluded returns PROBLEM.

- [ ] **Step 2: Run** `./mvnw -q test -Dtest='AnswerGraderTest,RevisionControllerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: revision loop with grounded grading, override and flag"`

---

### Task 9: System design problems

**Files:**
- Create: `design/DesignService.java`, `design/DesignController.java`, `resources/prompts/grade-design.txt`
- Test: `design/DesignControllerTest.java`

**Interfaces:**
- Consumes: `Scheduler.apply`, `Scheduler.applyProblem`, `JsonLlm`, `Attempt` (with `problem` set and `question` null).
- Produces:
  - `GET /api/problems/{id}` → `ProblemDto(Long id, String title, List<String> requirements, String chapterTitle, String bookTitle)`.
  - `POST /api/problems/{id}/submit` with body `DesignSubmission(List<Node> nodes, List<Edge> edges, String notes)`, where `Node(String id, String label)` and `Edge(String from, String to, String label)`.
    - Empty `nodes` throws `IllegalArgumentException("Add at least one box before submitting.")`, which becomes 400.
    - The user message sent to the AI is structured text: `Boxes:` (one `- label` per line), `Connections:` (`- A -> B (label)`), and `Notes:`. Labels are resolved by node ID.
    - The `grade-design` contract is `DesignGrade(List<Long> coveredPointIds, List<Long> missedPointIds, String feedback, String differences)`. The AI receives the requirements, the reference components and decisions, and the problem's points as `id | text | pages`.
    - Prompt rules: compare only with the book's design, credit valid alternatives that differ from the book (explain the trade-off in `differences` and don't count them as missed), never invent requirements, and give page references in the feedback.
    - Server side: drop IDs that aren't among the problem's points. A point in both lists counts as covered. For each covered point, save a CORRECT Attempt and apply it; for each missed point, save a WRONG Attempt and apply it. Points in neither list are untouched. Then `applyProblem(problem, missed.isEmpty())`.
    - Returns `DesignResultDto(String feedback, String differences, List<PointResultDto> points)`, where `PointResultDto(Long pointId, String text, int firstPage, int lastPage, Verdict verdict, Long attemptId)`.
    - Override for a design point goes through the existing `POST /api/attempts/{id}/override`.

- [ ] **Step 1: Write failing tests** `DesignControllerTest`:
- `emptyCanvasIsRejected` (400, with that message)
- `submissionSentAsStructuredText`: the FakeLlm's recorded user message contains `"- Load balancer"` and `"- Client -> Load balancer"`.
- `coveredAndMissedPointsUpdateScheduleAndProblem`: a problem with points p1, p2 and p3. The fake returns covered [p1, 999] and missed [p2]. Result: p1 is known well, p2 is WRONG, p3 is unchanged, there are exactly 2 attempts, and the problem has `box == 1`, `dueAt == now + 1 day`.
- `allCoveredAdvancesProblemBox`
- `outageReturns503AndChangesNothing`

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=DesignControllerTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: system design problems graded against the book"`

---

### Task 10: Library — upload, import from folder, retry

**Files:**
- Create: `library/LibraryService.java`, `library/LibraryController.java`, `library/DuplicateResourceException.java`
- Modify: `web/ApiExceptionHandler.java` (add `DuplicateResourceException` → 409 `{"error":<message>}`)
- Test: `library/LibraryControllerTest.java`

**Interfaces:**
- Consumes: `IngestionService.start`, `@Value app.books-dir`, `@Value app.data-dir`.
- Produces:
  - `GET /api/resources` → `List<ResourceDto(Long id, String title, ResourceStatus status, String failureReason, int totalPages, int itemsReady, int itemsTotal)>`.
  - `GET /api/resources/available` → file names of `*.pdf` directly in `app.books-dir` whose SHA-256 isn't already imported, sorted.
  - `POST /api/resources/import` with `{"fileName":"..."}`. The file name must be a plain name with no `/` and no `..`, otherwise 400. Imports the file from `app.books-dir`.
  - `POST /api/resources` (multipart `file`, `.pdf` only, otherwise 400).
  - Both imports compute SHA-256 and throw `DuplicateResourceException("This book is already in your library.")` (409) if it already exists. They copy the file to `<data-dir>/books/<sha256>.pdf` and create the Resource with:
    - `title` = the PDF metadata title, or the file name without `.pdf` if there is none
    - `status = INGESTING`
  - Then they call `ingestionService.start(id)` and return `ResourceDto` (201).
  - `POST /api/resources/{id}/retry`: only for FAILED resources (otherwise 400). Sets the status to INGESTING and calls `start` → 204.

- [ ] **Step 1: Write failing tests** `LibraryControllerTest`. These use a temporary `app.books-dir` holding the fixture PDF from Task 5 and a bookmark-free PDF made with PDFBox. `IngestionService` is a real bean with the FakeLlm. Use `@MockitoSpyBean` on `start` to run synchronously, or call `run` directly.
- `availableListsUnimportedPdfs`
- `importStartsIngestionAndHidesFromAvailable`
- `duplicateImportReturns409`
- `pathTraversalRejected` (`"../x.pdf"` → 400)
- `importOfPdfWithoutBookmarksShowsFailedReason`: after ingestion, `GET /api/resources` shows FAILED with the reason starting `"This PDF has no bookmarks"`.
- `retryRestartsFailedResource`

- [ ] **Step 2: Run** `./mvnw -q test -Dtest=LibraryControllerTest`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full backend suite** `./mvnw -q test`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat: library import, upload, duplicate guard and retry"`

---

### Task 11: Frontend — API client and Home

**Files:**
- Create: `frontend/src/api.ts`, `frontend/src/App.tsx` (replace the starter), `frontend/src/pages/HomePage.tsx`, `frontend/src/components/ProgressBar.tsx`, `frontend/src/components/QueueList.tsx`, `frontend/src/index.css` (replace the starter)
- Test: `frontend/src/pages/HomePage.test.tsx`

**Interfaces:**
- Consumes: the backend endpoints from Tasks 6–10.
- Produces:
  - `api.ts` exports TypeScript types mirroring the DTOs above (same field names) and these functions:
    - `getProgress()`, `getQueue(limit=8)`, `markLearned(id)`, `hideItem(id)`, `unhideItem(id)`, `getHidden()`
    - `getNext(answered, exclude: number[])`, `submitAnswer(qid, answer)`, `overrideAttempt(id)`, `flagQuestion(id)`
    - `getProblem(id)`, `submitDesign(id, submission)`
    - `getResources()`, `getAvailable()`, `importBook(fileName)`, `uploadBook(file)`, `retryResource(id)`
  - Each one rejects with `ApiError(status, message)`, where the message comes from the body's `error` field when there is one.
  - Routes: `/` HomePage, `/revise` RevisionPage, `/library` LibraryPage. A top nav has "Home · Revise · Library".
  - `ProgressBar({label, bar})` renders `"{percent}% known well · {knownWell} of {total} points"`.
  - HomePage, top to bottom:
    - the overall ProgressBar and the per-book bars (a FAILED book shows its `failureReason`)
    - a wins line, `"{n} weak points fixed this week"`, shown only when n > 0, plus `"Solid: {chapter titles}"`
    - a Revise button labelled `"Revise ({dueCount} due)"` linking to `/revise`
    - "Up next": QueueList, where the first item is highlighted and has a **Learned it** button, and every item shows `book · chapter` and `pp. {start}–{end}` plus a small "Hide" link
    - `"Still reading your book… more items will appear."` when `preparing` is true
  - Empty states:
    - no resources: `"Add a book in Library to get started."` with a link
    - queue empty and not preparing: `"You've covered everything in your books. Keep revising."`
  - Clicking **Learned it** calls `markLearned`, then refetches the queue and progress.

- [ ] **Step 1: Write failing test** `HomePage.test.tsx` (mock `fetch`):
- `showsProgressQueueAndDueCount`: shows "50% known well · 2 of 4 points", "Revise (3 due)", and the first queue item's "pp. 119–124" with a "Learned it" button
- `learnedItRefetches`: clicking Learned it POSTs `/api/map-items/{id}/learned` and the next item is then shown first
- `emptyLibraryPromptsToAddBook`

- [ ] **Step 2: Run** `cd frontend && npm test`. Expected: FAIL.
- [ ] **Step 3: Implement.** Use plain CSS with a calm, readable layout. Define colours as CSS variables, with a dark-mode `@media (prefers-color-scheme: dark)` override.
- [ ] **Step 4: Run** `npm test`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(ui): home with progress, wins and up-next queue"`

---

### Task 12: Frontend — Revision

**Files:**
- Create: `frontend/src/pages/RevisionPage.tsx`, `frontend/src/components/QuestionCard.tsx`, `frontend/src/components/FeedbackCard.tsx`
- Test: `frontend/src/pages/RevisionPage.test.tsx`

**Interfaces:**
- Consumes: `getNext`, `submitAnswer`, `overrideAttempt`, `flagQuestion`, and `DesignProblem` (Task 13). Until Task 13 lands, render a placeholder for `type === "PROBLEM"`.
- Produces: RevisionPage keeps `answered` (a count) and `seen` (point IDs) in component state. It fetches `getNext(answered, seen)`.
  - **QUESTION:** QuestionCard shows `book · chapter`, the prompt, a textarea (a single-line input for QUICKFIRE), and **Check**. Enter submits for QUICKFIRE; Ctrl+Enter submits for SHORT.
  - **After grading:** FeedbackCard shows the verdict ("Right" / "Partly right" / "Not quite"), the feedback, a "Book's answer" section with the model answer, and `"{bookTitle}, p. {first}–{last}"`. When `notInBook` is true it adds the note "The book doesn't settle this one." It also has:
    - **I was actually right** (shown when the verdict isn't CORRECT; calls override, then shows "Counted as right")
    - **Flag question** (calls flag, then shows "Won't ask this again")
    - **Next**, which increments `answered`, adds `pointId` to `seen`, and fetches the next item
  - **Grading fails:** the error message appears above the card, the typed answer stays in the box, and Check is enabled again.
  - **DONE:** `"All caught up — nothing due right now."` with a link home.
  - **Stop** is always visible and links to `/`. Nothing is lost, because progress is saved per answer.

- [ ] **Step 1: Write failing test** `RevisionPage.test.tsx`:
- `answersAndShowsGroundedFeedback`: shows "Partly right", the feedback text, and "System Design Interview, p. 52–52"
- `overrideCallsApi`
- `keepsAnswerWhenGradingFails`: a 503 response shows the error text and keeps the textarea's value
- `nextSendsAnsweredAndExclude`: the second `getNext` URL contains `answered=1&exclude=<pointId>`
- `doneState`

- [ ] **Step 2: Run** `npm test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(ui): revision flow with feedback, override and flag"`

---

### Task 13: Frontend — Design canvas

**Files:**
- Create: `frontend/src/components/DesignProblem.tsx`
- Modify: `frontend/src/pages/RevisionPage.tsx` (render `DesignProblem` for `type === "PROBLEM"` and call `onDone` to fetch the next item)
- Test: `frontend/src/components/DesignProblem.test.tsx`

**Interfaces:**
- Consumes: `getProblem`, `submitDesign`, `overrideAttempt`.
- Produces: `DesignProblem({problemId, onDone})`, laid out as three columns:
  - **Palette:** Client, Load balancer, Web server, Cache, Database, Queue, CDN, ID generator, and "+ Custom box", which prompts for a label through an inline input, not `window.prompt`. Click or drag adds a node at the centre of the canvas.
  - **Canvas:** `@xyflow/react` with draggable nodes and connections made by dragging handles. An edge label is editable by double-click, using an inline input.
  - **Notes:** a textarea.
  - Above the canvas: the title, `book · chapter`, and the requirements as a list.
  - **Submit for review** sends `{nodes:[{id,label}], edges:[{from,to,label}], notes}`.
  - **Result:** the feedback, a "Different from the book" section (only when `differences` is non-empty), and a list of points, each with ✓/✗, its text and `p. a–b`. Each ✗ has **I was actually right**.
  - Then **Next** calls `onDone()`. Errors show inline, and the canvas is kept.

- [ ] **Step 1: Write failing test** `DesignProblem.test.tsx`:
- `paletteClickAddsNodeAndSubmitSendsStructure`: click "Load balancer" and "Database", type notes, submit. The request body has 2 nodes with those labels and the notes.
- `showsCoveredAndMissedPoints`
- `emptyCanvasErrorShown`: the 400 message "Add at least one box before submitting." is displayed

Mock `ResizeObserver` for React Flow in `setupTests.ts`.

- [ ] **Step 2: Run** `npm test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(ui): drag-and-drop system design problems"`

---

### Task 14: Frontend — Library

**Files:**
- Create: `frontend/src/pages/LibraryPage.tsx`
- Test: `frontend/src/pages/LibraryPage.test.tsx`

**Interfaces:**
- Consumes: `getResources`, `getAvailable`, `importBook`, `uploadBook`, `retryResource`, `getHidden`, `unhideItem`.
- Produces: LibraryPage has three sections:
  - **Your books:** one row per book with the title and its status. INGESTING shows `"Reading… {itemsReady}/{itemsTotal} sections"`, READY shows "Ready", and FAILED shows the reason plus a **Retry** button. The page polls every 5 s while any book is INGESTING.
  - **In your project folder:** the available PDFs, each with an **Add** button, followed by an **Upload a PDF** file input.
  - **Hidden items:** each with **Restore**.
  - A 409 or 400 error shows its message inline.

- [ ] **Step 1: Write failing test** `LibraryPage.test.tsx`:
- `listsAvailableAndAdds`
- `showsFailedReasonWithRetry`
- `duplicateShows409Message`
- `restoresHiddenItem`

- [ ] **Step 2: Run** `npm test`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(ui): library with import, upload, retry and hidden items"`

---

### Task 15: Live end-to-end check with Alex Xu

**Files:** none (verification only; fix bugs in the owning task's files if found)

- [ ] **Step 1:** Run `cd backend && ./mvnw -q test && cd ../frontend && npm test`. Expected: all PASS.
- [ ] **Step 2:** Start the app with `rm -rf data && ./run.sh`. The browser opens on Home, showing "Add a book in Library to get started."
- [ ] **Step 3:** In Library, click **Add** next to `System Design Interview by Alex Xu.pdf`. It shows "Reading…" and the section count rises. Within a few minutes, Home shows the first Up next item from **Chapter 1** with page numbers starting at 5.
- [ ] **Step 4:** Check the content against the PDF:
  - No item comes from the title page, Forward, Chapter 16 or the Afterword.
  - The pages cited for 3 sample points contain the claimed text when opened in a PDF reader.
  - Chapter 2 has QUICKFIRE questions such as latency numbers or powers of two.
- [ ] **Step 5:** Click **Learned it** on the first item, then **Revise**. Answer one question well and one badly. The bar rises for the first. For the second, the "Not quite" feedback cites a page, and **I was actually right** raises the bar.
- [ ] **Step 6:** Stop the app in the middle of ingestion, run `./run.sh` again, and confirm Library resumes "Reading…" with no duplicate points. Check with the H2 console or a quick count query: `SELECT map_item_id, COUNT(*) FROM point GROUP BY map_item_id` matches the counts before the restart.
- [ ] **Step 7:** When chapter 4 finishes ingesting, mark all its items learned. Set the problem's `dueAt` to now through the H2 console, or wait a day. Revise until "Design a rate limiter" appears. Build a design on the canvas and submit it. The feedback lists covered and missed points with pages.
- [ ] **Step 8:** Add `Web Scalability For Startup Engineers PDF.pdf`. It shows FAILED with the no-bookmarks message, and the rest of the app keeps working.
- [ ] **Step 9: Commit** any fixes with `git commit -m "fix: issues found in live end-to-end check"`.

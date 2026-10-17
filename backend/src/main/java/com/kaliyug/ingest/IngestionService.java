package com.kaliyug.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.ChapterRepository;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.ingest.AiContracts.ChapterKindItem;
import com.kaliyug.ingest.AiContracts.ChapterKinds;
import com.kaliyug.ingest.AiContracts.ExtractedPoint;
import com.kaliyug.ingest.AiContracts.ExtractedPoints;
import com.kaliyug.ingest.AiContracts.ExtractedQuestion;
import com.kaliyug.ingest.AiContracts.ProblemSpec;
import com.kaliyug.ingest.AiContracts.RefComponent;
import com.kaliyug.ingest.AiContracts.RefDecision;
import com.kaliyug.ingest.AiContracts.Reference;
import com.kaliyug.ingest.AiContracts.Section;
import com.kaliyug.ingest.AiContracts.Sections;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;

/**
 * Turns a PDF into chapters, map items, points, questions and design problems.
 * Every step checks what already exists, so running it again after an interruption picks up where it stopped.
 */
@Service
public class IngestionService {

    static final String AI_UNREACHABLE = "AI service unreachable — press Retry in Library.";
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_RETRIES = 5;
    private static final int CLASSIFY_PREVIEW_CHARS = 800;
    /** Keeps each request well under Groq's 8,000 tokens-per-minute limit per key (about 4 characters per token). */
    static final int DEFAULT_MAX_INPUT_CHARS = 16_000;
    private static final int FALLBACK_SECTION_PAGES = 4;
    private static final int MIN_PAGE_CHARS = 80;

    private final ResourceRepository resources;
    private final PageTextRepository pages;
    private final ChapterRepository chapters;
    private final MapItemRepository mapItems;
    private final PointRepository points;
    private final QuestionRepository questions;
    private final ProblemRepository problems;
    private final JsonLlm ai;
    private final Prompts prompts;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ExecutorService executor;
    private Duration retryDelay = Duration.ofSeconds(60);
    private int maxInputChars = DEFAULT_MAX_INPUT_CHARS;

    public IngestionService(ResourceRepository resources, PageTextRepository pages, ChapterRepository chapters,
            MapItemRepository mapItems, PointRepository points, QuestionRepository questions,
            ProblemRepository problems, JsonLlm ai, Prompts prompts, ObjectMapper mapper, TransactionTemplate tx,
            ExecutorService ingestionExecutor) {
        this.resources = resources;
        this.pages = pages;
        this.chapters = chapters;
        this.mapItems = mapItems;
        this.points = points;
        this.questions = questions;
        this.problems = problems;
        this.ai = ai;
        this.prompts = prompts;
        this.mapper = mapper;
        this.tx = tx;
        this.executor = ingestionExecutor;
    }

    void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public void start(Long resourceId) {
        executor.submit(() -> {
            try {
                run(resourceId);
            } catch (RuntimeException e) {
                log.error("Ingestion of resource {} failed", resourceId, e);
                fail(resourceId, "Something went wrong while reading this book: " + e.getMessage());
            }
        });
    }

    public void run(Long resourceId) {
        Resource resource = resources.findById(resourceId).orElseThrow();
        if (resource.getStatus() != ResourceStatus.INGESTING) {
            return;
        }
        try {
            savePages(resource);
            if (!saveChapters(resource)) {
                return;
            }
            withRetries(() -> classifyChapters(resourceId));
            for (Chapter chapter : contentChapters(resourceId)) {
                if (!chapter.isSectionsSplit()) {
                    withRetries(() -> splitSections(resourceId, chapter));
                }
            }
            for (Chapter chapter : contentChapters(resourceId)) {
                for (MapItem item : mapItems.findByChapterIdOrderByOrderIndex(chapter.getId())) {
                    if (!item.isIngested()) {
                        withRetries(() -> extractPoints(resourceId, item));
                    }
                }
                if (chapter.getKind() == ChapterKind.DESIGN && problems.findByChapterId(chapter.getId()).isEmpty()) {
                    withRetries(() -> generateProblem(resourceId, chapter));
                }
            }
            setStatus(resourceId, ResourceStatus.READY, null);
        } catch (LlmUnavailableException e) {
            log.warn("Giving up on resource {} for now: {}", resourceId, e.getMessage());
            fail(resourceId, AI_UNREACHABLE);
        }
    }

    // Step 1
    private void savePages(Resource resource) {
        if (pages.existsByResourceId(resource.getId())) {
            return;
        }
        try (PdfBook book = PdfBook.open(Path.of(resource.getStoredPath()))) {
            int count = book.pageCount();
            List<PageText> texts = new ArrayList<>(count);
            for (int n = 1; n <= count; n++) {
                texts.add(new PageText(resource, n, book.pageText(n)));
            }
            tx.executeWithoutResult(s -> {
                pages.saveAll(texts);
                Resource fresh = resources.findById(resource.getId()).orElseThrow();
                fresh.setTotalPages(count);
                resources.save(fresh);
            });
        }
    }

    // Step 2: returns false when the book can't be split into chapters
    private boolean saveChapters(Resource resource) {
        if (!chapters.findByResourceIdOrderByOrderIndex(resource.getId()).isEmpty()) {
            return true;
        }
        List<OutlineChapter> outline;
        try (PdfBook book = PdfBook.open(Path.of(resource.getStoredPath()))) {
            outline = book.chapters();
        } catch (IllegalArgumentException e) {
            fail(resource.getId(), e.getMessage());
            return false;
        }
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < outline.size(); i++) {
                OutlineChapter c = outline.get(i);
                chapters.save(new Chapter(resource, i, c.title(), c.startPage(), c.endPage(), ChapterKind.UNCLASSIFIED));
            }
        });
        return true;
    }

    // Step 3
    private void classifyChapters(Long resourceId) {
        List<Chapter> all = chapters.findByResourceIdOrderByOrderIndex(resourceId);
        if (all.stream().noneMatch(c -> c.getKind() == ChapterKind.UNCLASSIFIED)) {
            return;
        }
        StringBuilder user = new StringBuilder("Chapters (index | title | start of first page):\n");
        for (int i = 0; i < all.size(); i++) {
            Chapter c = all.get(i);
            String first = pageText(resourceId, c.getStartPage(), c.getStartPage());
            String preview = first.substring(0, Math.min(CLASSIFY_PREVIEW_CHARS, first.length())).replaceAll("\\s+", " ");
            user.append(i).append(" | ").append(c.getTitle()).append(" | ").append(preview).append('\n');
        }
        ChapterKinds reply = ai.ask(prompts.get("classify-chapters"), user.toString(), ChapterKinds.class);
        Map<Integer, ChapterKind> kinds = new LinkedHashMap<>();
        for (ChapterKindItem item : nullSafe(reply.chapters())) {
            if (item.kind() != null && item.kind() != ChapterKind.UNCLASSIFIED) {
                kinds.put(item.index(), item.kind());
            }
        }
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < all.size(); i++) {
                Chapter c = all.get(i);
                c.setKind(kinds.getOrDefault(i, ChapterKind.CONCEPT));
                chapters.save(c);
            }
        });
    }

    // Step 4
    private void splitSections(Long resourceId, Chapter chapter) {
        String text = compact(markedPages(resourceId, chapter.getStartPage(), chapter.getEndPage()));
        String user = "Chapter: " + chapter.getTitle() + " (pages " + chapter.getStartPage() + "-" + chapter.getEndPage() + ")\n\n" + text;
        List<Section> sections = nullSafe(ai.ask(prompts.get("split-sections"), user, Sections.class).sections());
        if (!Grounding.validSections(sections, chapter.getStartPage(), chapter.getEndPage())) {
            String retry = user + "\n\nYour previous sections did not cover pages " + chapter.getStartPage() + "-"
                    + chapter.getEndPage() + " contiguously: the first section must start on page " + chapter.getStartPage()
                    + ", the last must end on page " + chapter.getEndPage()
                    + ", and each section must start on the previous section's last page or the page after it.";
            sections = nullSafe(ai.ask(prompts.get("split-sections"), retry, Sections.class).sections());
        }
        if (!Grounding.validSections(sections, chapter.getStartPage(), chapter.getEndPage())) {
            sections = pageWindows(chapter.getStartPage(), chapter.getEndPage(), FALLBACK_SECTION_PAGES).stream()
                    .map(w -> new Section(chapter.getTitle() + " (" + pagesLabel(w[0], w[1]) + ")", w[0], w[1]))
                    .toList();
        }
        List<Section> chosen = sections;
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < chosen.size(); i++) {
                Section section = chosen.get(i);
                String title = section.title() == null || section.title().isBlank() ? chapter.getTitle() : section.title().trim();
                mapItems.save(new MapItem(chapter, i, title, section.startPage(), section.endPage()));
            }
            Chapter fresh = chapters.findById(chapter.getId()).orElseThrow();
            fresh.setSectionsSplit(true);
            chapters.save(fresh);
        });
    }

    // Step 5
    private void extractPoints(Long resourceId, MapItem item) {
        String plain = pageText(resourceId, item.getStartPage(), item.getEndPage());
        String header = "Section: " + item.getTitle() + " (pages " + item.getStartPage() + "-" + item.getEndPage() + ")\n\n";
        List<ExtractedPoint> extracted = new ArrayList<>();
        for (String chunk : chunks(markedPages(resourceId, item.getStartPage(), item.getEndPage()))) {
            extracted.addAll(nullSafe(ai.ask(prompts.get("extract-points"), header + chunk, ExtractedPoints.class).points()));
        }
        int[] kept = {0};
        tx.executeWithoutResult(s -> {
            points.deleteByMapItemId(item.getId());
            MapItem fresh = mapItems.findById(item.getId()).orElseThrow();
            for (ExtractedPoint e : extracted) {
                if (!grounded(e, fresh, plain)) {
                    continue;
                }
                PointKind kind = e.kind() == null ? PointKind.CONCEPT : e.kind();
                List<ExtractedQuestion> usable = nullSafe(e.questions()).stream()
                        .filter(q -> notBlank(q.prompt()) && notBlank(q.modelAnswer()))
                        .toList();
                if (usable.isEmpty()) {
                    continue;
                }
                Point point = points.save(new Point(fresh, kind, e.text().trim(), e.firstPage(), e.lastPage(), e.evidence().trim()));
                for (ExtractedQuestion q : usable) {
                    QuestionType type = q.type() != null ? q.type() : kind == PointKind.NUMBER ? QuestionType.QUICKFIRE : QuestionType.SHORT;
                    questions.save(new Question(point, type, q.prompt().trim(), q.modelAnswer().trim()));
                }
                kept[0]++;
            }
            fresh.setIngested(true);
            mapItems.save(fresh);
        });
        log.info("Section '{}': kept {} of {} points", item.getTitle(), kept[0], extracted.size());
    }

    private static boolean grounded(ExtractedPoint e, MapItem item, String itemText) {
        return notBlank(e.text())
                && e.firstPage() >= item.getStartPage() && e.lastPage() <= item.getEndPage()
                && e.firstPage() <= e.lastPage()
                && Grounding.evidenceFound(e.evidence(), itemText);
    }

    // Step 6
    private void generateProblem(Long resourceId, Chapter chapter) {
        List<Point> chapterPoints = new ArrayList<>();
        for (MapItem item : mapItems.findByChapterIdOrderByOrderIndex(chapter.getId())) {
            chapterPoints.addAll(points.findByMapItemId(item.getId()));
        }
        Set<Long> allowed = chapterPoints.stream().map(Point::getId).collect(Collectors.toSet());
        StringBuilder user = new StringBuilder("Chapter: " + chapter.getTitle() + "\n\nPoints (id | text | pages):\n");
        for (Point p : chapterPoints) {
            user.append(p.getId()).append(" | ").append(p.getText()).append(" | ").append(pagesLabel(p)).append('\n');
        }
        List<String> opening = chunks(markedPages(resourceId, chapter.getStartPage(), chapter.getEndPage()),
                Math.max(maxInputChars - user.length(), maxInputChars / 4));
        user.append("\nChapter text (opening pages):\n").append(opening.isEmpty() ? "" : opening.get(0));
        ProblemSpec spec = ai.ask(prompts.get("generate-problem"), user.toString(), ProblemSpec.class);

        Set<Long> used = new HashSet<>();
        List<RefComponent> components = nullSafe(spec.components()).stream()
                .map(c -> new RefComponent(c.name(), c.role(), keep(c.pointIds(), allowed, used)))
                .toList();
        List<RefDecision> decisions = nullSafe(spec.decisions()).stream()
                .map(d -> new RefDecision(d.decision(), keep(d.pointIds(), allowed, used)))
                .toList();
        String title = notBlank(spec.title()) ? spec.title().trim() : chapter.getTitle();
        String requirementsJson = mapper.writeValueAsString(nullSafe(spec.requirements()));
        String referenceJson = mapper.writeValueAsString(new Reference(components, decisions));
        tx.executeWithoutResult(s -> {
            Problem problem = new Problem(chapters.findById(chapter.getId()).orElseThrow(), title, requirementsJson, referenceJson);
            problem.setPoints(new HashSet<>(points.findAllById(used)));
            problems.save(problem);
        });
    }

    private static List<Long> keep(List<Long> ids, Set<Long> allowed, Set<Long> used) {
        List<Long> kept = nullSafe(ids).stream().filter(allowed::contains).distinct().toList();
        used.addAll(kept);
        return kept;
    }

    private List<Chapter> contentChapters(Long resourceId) {
        return chapters.findByResourceIdOrderByOrderIndex(resourceId).stream()
                .filter(c -> c.getKind() != ChapterKind.FILLER)
                .toList();
    }

    private String pageText(Long resourceId, int from, int to) {
        return pages.findByResourceIdAndPageNumberBetweenOrderByPageNumber(resourceId, from, to).stream()
                .map(PageText::getText)
                .collect(Collectors.joining("\n"));
    }

    private List<String> markedPages(Long resourceId, int from, int to) {
        return pages.findByResourceIdAndPageNumberBetweenOrderByPageNumber(resourceId, from, to).stream()
                .map(p -> "=== PAGE " + p.getPageNumber() + " ===\n" + p.getText())
                .toList();
    }

    private List<String> chunks(List<String> markedPages) {
        return chunks(markedPages, maxInputChars);
    }

    /** Packs whole pages into chunks of at most {@code budget} characters; a page longer than the budget is cut. */
    static List<String> chunks(List<String> markedPages, int budget) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String page : markedPages) {
            String fitted = page.length() > budget ? page.substring(0, budget) : page;
            if (current.length() > 0 && current.length() + 1 + fitted.length() > budget) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(fitted);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** The whole chapter when it fits; otherwise the start of every page, so headings and page markers survive. */
    private String compact(List<String> markedPages) {
        String full = String.join("\n", markedPages);
        if (full.length() <= maxInputChars || markedPages.isEmpty()) {
            return full;
        }
        int perPage = Math.max(MIN_PAGE_CHARS, maxInputChars / markedPages.size());
        return markedPages.stream()
                .map(p -> p.length() > perPage ? p.substring(0, perPage) + " …" : p)
                .collect(Collectors.joining("\n"));
    }

    /** Consecutive page ranges of {@code size} pages covering start..end. */
    static List<int[]> pageWindows(int start, int end, int size) {
        List<int[]> windows = new ArrayList<>();
        for (int from = start; from <= end; from += size) {
            windows.add(new int[] {from, Math.min(end, from + size - 1)});
        }
        return windows;
    }

    static String pagesLabel(int first, int last) {
        return first == last ? "p. " + first : "pp. " + first + "–" + last;
    }

    static String pagesLabel(Point p) {
        return pagesLabel(p.getFirstPage(), p.getLastPage());
    }

    private void withRetries(Runnable step) {
        for (int attempt = 0; ; attempt++) {
            try {
                step.run();
                return;
            } catch (LlmUnavailableException e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
                log.warn("AI unavailable ({}), retrying step in {}", e.getMessage(), retryDelay);
                try {
                    Thread.sleep(retryDelay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void fail(Long resourceId, String reason) {
        setStatus(resourceId, ResourceStatus.FAILED, reason);
    }

    private void setStatus(Long resourceId, ResourceStatus status, String reason) {
        tx.executeWithoutResult(s -> {
            Resource r = resources.findById(resourceId).orElseThrow();
            r.setStatus(status);
            r.setFailureReason(reason);
            resources.save(r);
        });
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}

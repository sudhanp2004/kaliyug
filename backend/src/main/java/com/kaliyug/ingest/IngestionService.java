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
import org.springframework.beans.factory.annotation.Qualifier;
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
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.ingest.AiContracts.ChapterKindItem;
import com.kaliyug.ingest.AiContracts.ChapterKinds;
import com.kaliyug.ingest.AiContracts.ExtractedPoint;
import com.kaliyug.ingest.AiContracts.ExtractedPoints;
import com.kaliyug.ingest.AiContracts.ProblemSpec;
import com.kaliyug.ingest.AiContracts.RefComponent;
import com.kaliyug.ingest.AiContracts.RefDecision;
import com.kaliyug.ingest.AiContracts.Reference;
import com.kaliyug.ingest.AiContracts.Section;
import com.kaliyug.ingest.AiContracts.Sections;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.LlmBadReplyException;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;
import com.kaliyug.map.QueueService;

/**
 * Turns a PDF into chapters, map items, points, questions and design problems.
 * Every step checks what already exists, so running it again after an interruption picks up where it stopped.
 */
@Service
public class IngestionService {

    static final String AI_UNREACHABLE = "AI service unreachable — press Retry in Library.";
    static final String AI_UNUSABLE = "The AI kept giving unusable answers for one section — press Retry in Library.";
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MAX_RETRIES = 5;
    private static final int CLASSIFY_PREVIEW_CHARS = 800;
    /** Keeps each request well under Groq's 8,000 tokens-per-minute limit per key (about 4 characters per token). */
    public static final int DEFAULT_MAX_INPUT_CHARS = 16_000;
    private static final int FALLBACK_SECTION_PAGES = 4;
    private static final int MIN_PAGE_CHARS = 80;
    /** Title pages and forewords are 1-2 pages; anything longer labelled filler gets a second look with more of its text. */
    static final int DEFAULT_FILLER_RECHECK_MIN_PAGES = 3;
    private static final int RECHECK_PREVIEW_CHARS = 4_000;

    private final ResourceRepository resources;
    private final PageTextRepository pages;
    private final ChapterRepository chapters;
    private final MapItemRepository mapItems;
    private final PointRepository points;
    private final ProblemRepository problems;
    private final JsonLlm ai;
    private final Prompts prompts;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ExecutorService executor;
    private final QueueService queue;
    private final BookText bookText;
    private final PointWriter pointWriter;
    private Duration retryDelay = Duration.ofSeconds(60);
    private int maxInputChars = DEFAULT_MAX_INPUT_CHARS;
    private int fillerRecheckMinPages = DEFAULT_FILLER_RECHECK_MIN_PAGES;

    public IngestionService(ResourceRepository resources, PageTextRepository pages, ChapterRepository chapters,
            MapItemRepository mapItems, PointRepository points,
            ProblemRepository problems, JsonLlm ai, Prompts prompts, ObjectMapper mapper, TransactionTemplate tx,
            @Qualifier("ingestionExecutor") ExecutorService ingestionExecutor, QueueService queue, BookText bookText, PointWriter pointWriter) {
        this.queue = queue;
        this.bookText = bookText;
        this.pointWriter = pointWriter;
        this.resources = resources;
        this.pages = pages;
        this.chapters = chapters;
        this.mapItems = mapItems;
        this.points = points;
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

    void setFillerRecheckMinPages(int pages) {
        this.fillerRecheckMinPages = pages;
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
            fail(resourceId, e instanceof LlmBadReplyException ? AI_UNUSABLE : AI_UNREACHABLE);
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

    // Step 3: only chapters not yet classified; indexes in messages are the chapters' own order indexes
    private void classifyChapters(Long resourceId) {
        List<Chapter> todo = chapters.findByResourceIdOrderByOrderIndex(resourceId).stream()
                .filter(c -> c.getKind() == ChapterKind.UNCLASSIFIED)
                .toList();
        if (todo.isEmpty()) {
            return;
        }
        Map<Integer, ChapterKind> kinds = askKinds(resourceId, todo, CLASSIFY_PREVIEW_CHARS);
        List<Chapter> recheck = todo.stream()
                .filter(c -> kinds.get(c.getOrderIndex()) == ChapterKind.FILLER)
                .filter(c -> c.getEndPage() - c.getStartPage() + 1 >= fillerRecheckMinPages)
                .toList();
        for (Chapter c : recheck) {
            ChapterKind second = askKinds(resourceId, List.of(c), RECHECK_PREVIEW_CHARS).get(c.getOrderIndex());
            if (second != null) {
                kinds.put(c.getOrderIndex(), second);
            }
        }
        tx.executeWithoutResult(s -> {
            for (Chapter c : todo) {
                Chapter fresh = chapters.findById(c.getId()).orElseThrow();
                fresh.setKind(kinds.getOrDefault(c.getOrderIndex(), ChapterKind.CONCEPT));
                chapters.save(fresh);
            }
        });
    }

    private Map<Integer, ChapterKind> askKinds(Long resourceId, List<Chapter> list, int previewChars) {
        StringBuilder user = new StringBuilder("Chapters (index | title | start of the chapter):\n");
        for (Chapter c : list) {
            String start = bookText.plain(resourceId, c.getStartPage(), Math.min(c.getEndPage(), c.getStartPage() + 2));
            String preview = start.substring(0, Math.min(previewChars, start.length())).replaceAll("\\s+", " ");
            user.append(c.getOrderIndex()).append(" | ").append(c.getTitle()).append(" | ").append(preview).append('\n');
        }
        ChapterKinds reply = ai.ask(prompts.get("classify-chapters"), user.toString(), ChapterKinds.class);
        Map<Integer, ChapterKind> kinds = new LinkedHashMap<>();
        for (ChapterKindItem item : nullSafe(reply.chapters())) {
            if (item.kind() != null && item.kind() != ChapterKind.UNCLASSIFIED) {
                kinds.put(item.index(), item.kind());
            }
        }
        return kinds;
    }

    // Step 4
    private void splitSections(Long resourceId, Chapter chapter) {
        String text = compact(bookText.marked(resourceId, chapter.getStartPage(), chapter.getEndPage()));
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
        String plain = bookText.plain(resourceId, item.getStartPage(), item.getEndPage());
        String header = "Section: " + item.getTitle() + " (pages " + item.getStartPage() + "-" + item.getEndPage() + ")\n\n";
        List<ExtractedPoint> extracted = new ArrayList<>();
        for (BookText.Chunk chunk : bookText.chunks(resourceId, item.getStartPage(), item.getEndPage(), maxInputChars)) {
            extracted.addAll(nullSafe(ai.ask(prompts.get("extract-points"), header + chunk.text(), ExtractedPoints.class).points()));
        }
        int[] kept = {0};
        tx.executeWithoutResult(s -> {
            points.deleteByMapItemId(item.getId());
            MapItem fresh = mapItems.findById(item.getId()).orElseThrow();
            kept[0] = pointWriter.saveGrounded(fresh, extracted, plain).size();
            fresh.setIngested(true);
            mapItems.save(fresh);
        });
        log.info("Section '{}': kept {} of {} points", item.getTitle(), kept[0], extracted.size());
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
        List<BookText.Chunk> opening = bookText.chunks(resourceId, chapter.getStartPage(), chapter.getEndPage(),
                Math.max(maxInputChars - user.length(), maxInputChars / 4));
        user.append("\nChapter text (opening pages):\n").append(opening.isEmpty() ? "" : opening.get(0).text());
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
        queue.scheduleProblemIfChapterDone(chapter.getId());
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

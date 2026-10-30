package com.kaliyug.lesson;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Lesson;
import com.kaliyug.domain.LessonRepository;
import com.kaliyug.domain.LessonStatus;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.ingest.AiContracts.ExtractedPoint;
import com.kaliyug.ingest.AiContracts.ExtractedPoints;
import com.kaliyug.ingest.BookText;
import com.kaliyug.ingest.Grounding;
import com.kaliyug.ingest.IngestionService;
import com.kaliyug.ingest.PointWriter;
import com.kaliyug.lesson.LessonContracts.Block;
import com.kaliyug.lesson.LessonContracts.LessonContent;
import com.kaliyug.lesson.LessonContracts.LessonReply;
import com.kaliyug.lesson.LessonContracts.MiniDesignSpec;
import com.kaliyug.lesson.LessonContracts.Part;
import com.kaliyug.lesson.LessonContracts.Remember;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.LlmBadReplyException;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;
import com.kaliyug.revision.Scheduler;

/**
 * Writes the lesson for one section from its page text and points.
 * Each budget-sized chunk of the section gets a lesson call and a coverage call; the coverage call's
 * missing ideas become new grounded points, so revision asks about them too.
 */
@Service
public class LessonGenerator {

    static final String TOO_LITTLE_TEXT = "This section has almost no text the app can read (it may be mostly figures).";
    static final String AI_UNREACHABLE = "The AI is unreachable right now.";
    static final String AI_UNUSABLE = "The AI kept giving unusable lessons for this section.";
    static final int MIN_SECTION_CHARS = 200;
    static final int MAX_DIAGRAMS = 2;
    static final int READING_WPM = 180;
    private static final Logger log = LoggerFactory.getLogger(LessonGenerator.class);

    private final MapItemRepository mapItems;
    private final PointRepository points;
    private final LessonRepository lessons;
    private final BookText bookText;
    private final PointWriter pointWriter;
    private final Scheduler scheduler;
    private final JsonLlm ai;
    private final Prompts prompts;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private int maxInputChars = IngestionService.DEFAULT_MAX_INPUT_CHARS;

    public LessonGenerator(MapItemRepository mapItems, PointRepository points, LessonRepository lessons, BookText bookText,
            PointWriter pointWriter, Scheduler scheduler, JsonLlm ai, Prompts prompts, ObjectMapper mapper,
            TransactionTemplate tx, Clock clock) {
        this.mapItems = mapItems;
        this.points = points;
        this.lessons = lessons;
        this.bookText = bookText;
        this.pointWriter = pointWriter;
        this.scheduler = scheduler;
        this.ai = ai;
        this.prompts = prompts;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    private record KnownPoint(Long id, PointKind kind, String text, int firstPage, int lastPage) {
    }

    private record Section(Long itemId, String title, int start, int end, Long resourceId, String bookTitle,
            String chapterTitle, String plain, List<KnownPoint> points) {
    }

    /** Ends with the item's lesson READY or FAILED. Never throws for AI trouble. */
    public void generate(Long mapItemId) {
        Section s = tx.execute(st -> read(mapItemId));
        if (s == null) {
            return;
        }
        if (Grounding.normalize(s.plain()).length() < MIN_SECTION_CHARS) {
            fail(mapItemId, TOO_LITTLE_TEXT);
            return;
        }
        try {
            List<Block> blocks = new ArrayList<>();
            List<LessonReply> replies = new ArrayList<>();
            int newPoints = 0;
            for (BookText.Chunk chunk : bookText.chunks(s.resourceId(), s.start(), s.end(), maxInputChars * 11 / 16)) {
                List<KnownPoint> chunkPoints = s.points().stream()
                        .filter(p -> p.firstPage() >= chunk.firstPage() && p.firstPage() <= chunk.lastPage())
                        .toList();
                LessonReply reply = askLesson(s, chunk, chunkPoints);
                replies.add(reply);
                List<Remember> remember = new ArrayList<>(nullSafe(reply.remember()).stream()
                        .filter(r -> r != null && notBlank(r.text()) && within(r.firstPage(), r.lastPage(), s)
                                && Grounding.evidenceFound(r.evidence(), s.plain()))
                        .map(r -> new Remember(r.text().trim(), r.firstPage(), r.lastPage(), r.evidence().trim()))
                        .toList());
                Block block = new Block(reply.idea(), reply.howItWorks(), remember);
                List<Point> added = askCoverage(s, chunk, chunkPoints, block);
                for (Point p : added) {
                    remember.add(new Remember(p.getText(), p.getFirstPage(), p.getLastPage(), p.getEvidence()));
                }
                if (learned(mapItemId)) {
                    newPoints += added.size();
                }
                blocks.add(block);
            }
            LessonContent content = assemble(s, blocks, replies);
            int minutes = Math.max(1, (int) Math.ceil(words(blocks) / (double) READING_WPM));
            String json = mapper.writeValueAsString(content);
            int found = newPoints;
            update(mapItemId, l -> {
                l.setStatus(LessonStatus.READY);
                l.setContentJson(json);
                l.setReadingMinutes(minutes);
                l.setNewPointsFound(found);
                l.setFailureReason(null);
            });
        } catch (LlmUnavailableException e) {
            log.warn("Lesson for section {} failed: {}", mapItemId, e.getMessage());
            fail(mapItemId, e instanceof LlmBadReplyException ? AI_UNUSABLE : AI_UNREACHABLE);
        }
    }

    private Section read(Long mapItemId) {
        MapItem item = mapItems.findById(mapItemId).orElse(null);
        if (item == null || lessons.findByMapItemId(mapItemId).isEmpty()) {
            return null;
        }
        Long resourceId = item.getChapter().getResource().getId();
        List<KnownPoint> known = points.findByMapItemId(mapItemId).stream()
                .map(p -> new KnownPoint(p.getId(), p.getKind(), p.getText(), p.getFirstPage(), p.getLastPage()))
                .toList();
        return new Section(mapItemId, item.getTitle(), item.getStartPage(), item.getEndPage(), resourceId,
                item.getChapter().getResource().getTitle(), item.getChapter().getTitle(),
                bookText.plain(resourceId, item.getStartPage(), item.getEndPage()), known);
    }

    private LessonReply askLesson(Section s, BookText.Chunk chunk, List<KnownPoint> chunkPoints) {
        StringBuilder pointLines = new StringBuilder();
        for (KnownPoint p : chunkPoints) {
            String line = p.id() + " | " + p.kind() + " | " + p.text() + " | " + pages(p.firstPage(), p.lastPage()) + "\n";
            if (pointLines.length() + line.length() > maxInputChars / 4) {
                break;
            }
            pointLines.append(line);
        }
        String user = "Section: " + s.title() + " (pages " + s.start() + "-" + s.end() + ")\n"
                + "Book: " + s.bookTitle() + " · " + s.chapterTitle() + "\n\n"
                + "Points (id | kind | text | pages):\n" + pointLines + "\n" + chunk.text();
        LessonReply reply = ai.ask(prompts.get("lesson"), user, LessonReply.class);
        if (usable(reply, s)) {
            return reply;
        }
        String retry = user + "\n\nYour previous reply was unusable: \"idea\" and \"howItWorks\" must both have text, "
                + "and their pages must be within " + s.start() + "-" + s.end() + ".";
        reply = ai.ask(prompts.get("lesson"), retry, LessonReply.class);
        if (usable(reply, s)) {
            return reply;
        }
        throw new LlmBadReplyException("Lesson reply for section " + s.itemId() + " was unusable twice", null);
    }

    private List<Point> askCoverage(Section s, BookText.Chunk chunk, List<KnownPoint> chunkPoints, Block block) {
        String lessonSoFar = Stream.concat(
                        Stream.of(block.idea().text(), block.howItWorks().text()),
                        block.remember().stream().map(Remember::text))
                .collect(Collectors.joining("\n"));
        lessonSoFar = clip(lessonSoFar, maxInputChars * 3 / 16);
        StringBuilder known = new StringBuilder();
        for (KnownPoint p : chunkPoints) {
            String line = p.text() + "\n";
            if (known.length() + line.length() > maxInputChars / 8) {
                break;
            }
            known.append(line);
        }
        String user = "Section: " + s.title() + " (pages " + s.start() + "-" + s.end() + ")\n\n"
                + "Lesson so far:\n" + lessonSoFar + "\n\n"
                + "Points already known:\n" + known + "\n" + chunk.text();
        ExtractedPoints reply = ai.ask(prompts.get("lesson-coverage"), user, ExtractedPoints.class);
        return tx.execute(st -> {
            MapItem fresh = mapItems.findById(s.itemId()).orElseThrow();
            // The AI sees only part of what the section already has, so it can repeat a known idea; skip repeats.
            Set<String> seen = new java.util.HashSet<>();
            for (Point p : points.findByMapItemId(s.itemId())) {
                seen.add(Grounding.normalize(p.getText()));
                seen.add(Grounding.normalize(p.getEvidence()));
            }
            List<ExtractedPoint> newIdeas = new ArrayList<>();
            for (ExtractedPoint e : reply == null || reply.points() == null ? List.<ExtractedPoint>of() : reply.points()) {
                String text = Grounding.normalize(e.text());
                String evidence = Grounding.normalize(e.evidence());
                if (seen.contains(text) || seen.contains(evidence)) {
                    continue;
                }
                seen.add(text);
                seen.add(evidence);
                newIdeas.add(e);
            }
            List<Point> saved = pointWriter.saveGrounded(fresh, newIdeas, s.plain());
            if (fresh.isLearned()) {
                saved.forEach(scheduler::onLearned);
            }
            return saved;
        });
    }

    private LessonContent assemble(Section s, List<Block> blocks, List<LessonReply> replies) {
        List<String> diagrams = replies.stream()
                .flatMap(r -> nullSafe(r.diagrams()).stream())
                .filter(LessonGenerator::notBlank)
                .limit(MAX_DIAGRAMS)
                .toList();
        String leftOut = replies.stream().map(LessonReply::leftOut).filter(LessonGenerator::notBlank)
                .map(String::trim).collect(Collectors.joining(" "));
        String youtube = replies.stream().map(LessonReply::youtubeQuery).filter(LessonGenerator::notBlank)
                .map(String::trim).findFirst().orElse(s.title());
        Set<Long> own = s.points().stream().map(KnownPoint::id).collect(Collectors.toSet());
        MiniDesignSpec mini = replies.stream().map(LessonReply::miniDesign)
                .filter(m -> m != null && notBlank(m.task()))
                .findFirst()
                .map(m -> new MiniDesignSpec(m.task().trim(),
                        nullSafe(m.requirements()).stream().filter(LessonGenerator::notBlank).map(String::trim).toList(),
                        nullSafe(m.pointIds()).stream().filter(own::contains).distinct().toList()))
                .filter(m -> !m.pointIds().isEmpty())
                .orElse(null);
        return new LessonContent(blocks, diagrams, leftOut, youtube, mini);
    }

    private boolean learned(Long mapItemId) {
        return Boolean.TRUE.equals(tx.execute(st -> mapItems.findById(mapItemId).map(MapItem::isLearned).orElse(false)));
    }

    private static boolean usable(LessonReply r, Section s) {
        return r != null && usable(r.idea(), s) && usable(r.howItWorks(), s);
    }

    private static boolean usable(Part p, Section s) {
        return p != null && notBlank(p.text()) && within(p.firstPage(), p.lastPage(), s);
    }

    private static boolean within(int first, int last, Section s) {
        return s.start() <= first && first <= last && last <= s.end();
    }

    private static int words(List<Block> blocks) {
        return blocks.stream()
                .flatMap(b -> Stream.concat(Stream.of(b.idea().text(), b.howItWorks().text()),
                        b.remember().stream().map(Remember::text)))
                .mapToInt(t -> t.isBlank() ? 0 : t.trim().split("\\s+").length)
                .sum();
    }

    private void fail(Long mapItemId, String reason) {
        update(mapItemId, l -> {
            l.setStatus(LessonStatus.FAILED);
            l.setFailureReason(reason);
        });
    }

    private void update(Long mapItemId, java.util.function.Consumer<Lesson> change) {
        tx.executeWithoutResult(st -> lessons.findByMapItemId(mapItemId).ifPresent(l -> {
            change.accept(l);
            l.setUpdatedAt(clock.instant());
        }));
    }

    private static String pages(int first, int last) {
        return first == last ? "p. " + first : "pp. " + first + "-" + last;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}

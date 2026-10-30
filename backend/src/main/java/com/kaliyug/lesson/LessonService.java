package com.kaliyug.lesson;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.Lesson;
import com.kaliyug.domain.LessonRepository;
import com.kaliyug.domain.LessonStatus;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.lesson.LessonContracts.Block;
import com.kaliyug.lesson.LessonContracts.LessonContent;
import com.kaliyug.design.DesignService;
import com.kaliyug.design.DesignService.DesignResultDto;
import com.kaliyug.design.DesignService.DesignSubmission;
import com.kaliyug.lesson.LessonContracts.LinkChoice;
import com.kaliyug.lesson.LessonContracts.MiniDesignSpec;
import com.kaliyug.lesson.LessonContracts.Links;
import com.kaliyug.lesson.LessonContracts.LinksReply;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;
import com.kaliyug.ingest.IngestionService;
import com.kaliyug.map.QueueService;
import com.kaliyug.map.QueueService.QueueItemDto;
import com.kaliyug.revision.RevisionService.QuestionView;

/** Opens lessons, prepares upcoming ones in the background, and picks each lesson's quick check. */
@Service
public class LessonService {

    static final int PREPARE_AHEAD = 2;
    static final int QUICK_CHECK = 3;
    static final int MINI_DESIGN_MINUTES = 3;
    static final int MAX_LINKS = 3;
    private static final int KEY_POINT_CHARS = 100;
    static final String NOT_IN_SYLLABUS = "This section isn't in your syllabus.";
    static final String NO_MINI_DESIGN = "This lesson has no mini design.";
    static final String SOMETHING_WRONG = "Something went wrong while preparing this lesson.";
    private static final String YOUTUBE_SEARCH = "https://www.youtube.com/results?search_query=";
    private static final Logger log = LoggerFactory.getLogger(LessonService.class);

    public record LessonDto(Long mapItemId, LessonStatus status, String failureReason, String sectionTitle,
            String bookTitle, String chapterTitle, int startPage, int endPage, boolean learned,
            Integer estimatedMinutes, List<Block> blocks, List<String> diagrams, String leftOut, String youtubeUrl,
            List<LinkDto> buildsOn, List<QuestionView> quickCheck, MiniDesignDto miniDesign, int newPointsFound) {
    }

    public record LinkDto(Long mapItemId, String title, String bookTitle, String why) {
    }

    public record MiniDesignDto(String task, List<String> requirements) {
    }

    private final MapItemRepository mapItems;
    private final LessonRepository lessons;
    private final PointRepository points;
    private final QuestionRepository questions;
    private final QueueService queue;
    private final LessonGenerator generator;
    private final JsonLlm ai;
    private final DesignService design;
    private final Prompts prompts;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private Executor executor;

    public LessonService(MapItemRepository mapItems, LessonRepository lessons, PointRepository points,
            QuestionRepository questions, QueueService queue, LessonGenerator generator, JsonLlm ai, Prompts prompts, DesignService design, ObjectMapper mapper,
            TransactionTemplate tx, Clock clock, @Qualifier("lessonExecutor") Executor lessonExecutor) {
        this.mapItems = mapItems;
        this.lessons = lessons;
        this.points = points;
        this.questions = questions;
        this.queue = queue;
        this.generator = generator;
        this.ai = ai;
        this.design = design;
        this.prompts = prompts;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
        this.executor = lessonExecutor;
    }

    /** For tests: work queued on the old executor is abandoned, so nothing counts as running any more. */
    void setExecutor(Executor executor) {
        this.executor = executor;
        running.clear();
    }

    public LessonDto open(Long mapItemId) {
        visibleItem(mapItemId);
        ensure(mapItemId, true);
        queue.queue(PREPARE_AHEAD + 1).items().stream()
                .map(QueueItemDto::id)
                .filter(id -> !id.equals(mapItemId))
                .limit(PREPARE_AHEAD)
                .forEach(id -> ensure(id, false));
        return view(mapItemId);
    }

    public void prepareUpcoming() {
        queue.queue(PREPARE_AHEAD).items().forEach(i -> ensure(i.id(), false));
    }

    public LessonDto rewrite(Long mapItemId) {
        visibleItem(mapItemId);
        if (!running.contains(mapItemId)) {
            boolean exists = Boolean.TRUE.equals(tx.execute(s -> lessons.findByMapItemId(mapItemId).map(l -> {
                l.setStatus(LessonStatus.PREPARING);
                l.setContentJson(null);
                l.setFailureReason(null);
                l.setUpdatedAt(clock.instant());
                return true;
            }).orElse(false)));
            if (!exists) {
                ensure(mapItemId, true);
            } else {
                start(mapItemId);
            }
        }
        return view(mapItemId);
    }

    public LessonDto view(Long mapItemId) {
        refreshLinksIfStale(mapItemId);
        return tx.execute(s -> {
            MapItem item = visibleItem(mapItemId);
            Lesson lesson = lessons.findByMapItemId(mapItemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This lesson hasn't been prepared yet."));
            return toDto(item, lesson);
        });
    }

    /** Creates the lesson if needed and starts generating it unless it's ready (or failed, unless retrying). */
    private void ensure(Long mapItemId, boolean retryFailed) {
        LessonStatus status = tx.execute(s -> {
            Lesson lesson = lessons.findByMapItemId(mapItemId).orElse(null);
            if (lesson == null) {
                return null;
            }
            if (lesson.getStatus() == LessonStatus.FAILED && retryFailed) {
                lesson.setStatus(LessonStatus.PREPARING);
                lesson.setFailureReason(null);
                lesson.setUpdatedAt(clock.instant());
            }
            return lesson.getStatus();
        });
        if (status == null) {
            status = create(mapItemId);
        }
        if (status == LessonStatus.PREPARING) {
            start(mapItemId);
        }
    }

    private LessonStatus create(Long mapItemId) {
        try {
            return tx.execute(s -> {
                Lesson lesson = new Lesson(mapItems.findById(mapItemId).orElseThrow());
                lesson.setUpdatedAt(clock.instant());
                return lessons.saveAndFlush(lesson).getStatus();
            });
        } catch (DataIntegrityViolationException e) {
            // Created at the same moment by another request.
            return tx.execute(s -> lessons.findByMapItemId(mapItemId).map(Lesson::getStatus).orElseThrow());
        }
    }

    private void start(Long mapItemId) {
        if (!running.add(mapItemId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    generator.generate(mapItemId);
                } catch (RuntimeException e) {
                    log.error("Lesson for section {} failed", mapItemId, e);
                    markFailed(mapItemId);
                } finally {
                    running.remove(mapItemId);
                }
            });
        } catch (RejectedExecutionException e) {
            running.remove(mapItemId);
        }
    }

    private void markFailed(Long mapItemId) {
        tx.executeWithoutResult(s -> lessons.findByMapItemId(mapItemId).ifPresent(l -> {
            l.setStatus(LessonStatus.FAILED);
            l.setFailureReason(SOMETHING_WRONG);
            l.setUpdatedAt(clock.instant());
        }));
    }

    private MapItem visibleItem(Long mapItemId) {
        return mapItems.findById(mapItemId)
                .filter(i -> !i.isHidden())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_IN_SYLLABUS));
    }

    private LessonDto toDto(MapItem item, Lesson lesson) {
        Chapter chapter = item.getChapter();
        String bookTitle = chapter.getResource().getTitle();
        if (lesson.getStatus() != LessonStatus.READY || lesson.getContentJson() == null) {
            LessonStatus status = lesson.getStatus() == LessonStatus.READY ? LessonStatus.PREPARING : lesson.getStatus();
            return new LessonDto(item.getId(), status, lesson.getFailureReason(), item.getTitle(), bookTitle,
                    chapter.getTitle(), item.getStartPage(), item.getEndPage(), item.isLearned(), null, List.of(),
                    List.of(), "", null, List.of(), List.of(), null, lesson.getNewPointsFound());
        }
        LessonContent content = mapper.readValue(lesson.getContentJson(), LessonContent.class);
        MiniDesignDto mini = content.miniDesign() == null ? null
                : new MiniDesignDto(content.miniDesign().task(), content.miniDesign().requirements());
        List<QuestionView> quickCheck = quickCheck(item, content, bookTitle, chapter.getTitle());
        int minutes = lesson.getReadingMinutes() + quickCheck.size() + (mini != null ? MINI_DESIGN_MINUTES : 0);
        String youtube = YOUTUBE_SEARCH + URLEncoder.encode(content.youtubeQuery() == null ? item.getTitle() : content.youtubeQuery(),
                StandardCharsets.UTF_8);
        return new LessonDto(item.getId(), LessonStatus.READY, null, item.getTitle(), bookTitle, chapter.getTitle(),
                item.getStartPage(), item.getEndPage(), item.isLearned(), minutes, content.blocks(), content.diagrams(),
                content.leftOut() == null ? "" : content.leftOut(), youtube, buildsOn(lesson), quickCheck, mini,
                lesson.getNewPointsFound());
    }

    /** Grades the lesson's mini design against the points it should show. */
    public DesignResultDto submitDesign(Long mapItemId, DesignSubmission submission) {
        return tx.execute(s -> {
            visibleItem(mapItemId);
            Lesson lesson = lessons.findByMapItemId(mapItemId).orElse(null);
            LessonContent content = lesson == null || lesson.getStatus() != LessonStatus.READY || lesson.getContentJson() == null
                    ? null : mapper.readValue(lesson.getContentJson(), LessonContent.class);
            if (content == null || content.miniDesign() == null) {
                throw new IllegalArgumentException(NO_MINI_DESIGN);
            }
            MiniDesignSpec mini = content.miniDesign();
            List<Point> designPoints = points.findAllById(mini.pointIds()).stream()
                    .filter(p -> p.getMapItem().getId().equals(mapItemId))
                    .toList();
            String reference = content.blocks().stream()
                    .flatMap(b -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(b.idea().text(), b.howItWorks().text()),
                            b.remember().stream().map(LessonContracts.Remember::text)))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return design.grade(mini.task(), mini.requirements() == null ? List.of() : mini.requirements(), reference,
                    designPoints, submission, null);
        });
    }

    private record LinksRequest(String user, Set<Long> allowed, long learnedCount) {
    }

    /** Re-picks "Builds on" when more sections have been learned since the links were chosen. AI call outside a transaction. */
    private void refreshLinksIfStale(Long mapItemId) {
        LinksRequest request = tx.execute(s -> {
            Lesson lesson = lessons.findByMapItemId(mapItemId).orElse(null);
            long learnedCount = mapItems.countByLearnedAtIsNotNull();
            if (lesson == null || lesson.getStatus() != LessonStatus.READY || lesson.getContentJson() == null
                    || Long.valueOf(learnedCount).equals(lesson.getLinksAtLearnedCount())) {
                return null;
            }
            List<MapItem> candidates = mapItems.findByLearnedAtIsNotNullAndHiddenFalseOrderByLearnedAtDesc().stream()
                    .filter(m -> !m.getId().equals(mapItemId))
                    .toList();
            if (candidates.isEmpty()) {
                storeLinks(lesson, List.of(), learnedCount);
                return null;
            }
            LessonContent content = mapper.readValue(lesson.getContentJson(), LessonContent.class);
            String idea = content.blocks().isEmpty() || content.blocks().get(0).idea() == null ? ""
                    : content.blocks().get(0).idea().text();
            StringBuilder user = new StringBuilder("This lesson: " + lesson.getMapItem().getTitle() + " — " + idea + "\n\n"
                    + "Learned sections (id | book | chapter | section | key point):\n");
            Set<Long> allowed = new LinkedHashSet<>();
            for (MapItem m : candidates) {
                String keyPoint = points.findByMapItemId(m.getId()).stream()
                        .min(Comparator.comparing(Point::getId))
                        .map(p -> p.getText().length() > KEY_POINT_CHARS ? p.getText().substring(0, KEY_POINT_CHARS) : p.getText())
                        .orElse("");
                String line = m.getId() + " | " + m.getChapter().getResource().getTitle() + " | " + m.getChapter().getTitle()
                        + " | " + m.getTitle() + " | " + keyPoint + "\n";
                if (user.length() + line.length() > IngestionService.DEFAULT_MAX_INPUT_CHARS) {
                    break;
                }
                user.append(line);
                allowed.add(m.getId());
            }
            return new LinksRequest(user.toString(), allowed, learnedCount);
        });
        if (request == null) {
            return;
        }
        LinksReply reply;
        try {
            reply = ai.ask(prompts.get("lesson-links"), request.user(), LinksReply.class);
        } catch (LlmUnavailableException e) {
            log.warn("Couldn't pick links for section {}: {}", mapItemId, e.getMessage());
            return;
        }
        List<LinkChoice> chosen = (reply == null || reply.links() == null ? List.<LinkChoice>of() : reply.links()).stream()
                .filter(c -> c != null && c.mapItemId() != null && request.allowed().contains(c.mapItemId())
                        && c.why() != null && !c.why().isBlank())
                .map(c -> new LinkChoice(c.mapItemId(), c.why().trim()))
                .distinct()
                .limit(MAX_LINKS)
                .toList();
        tx.executeWithoutResult(s -> lessons.findByMapItemId(mapItemId)
                .ifPresent(l -> storeLinks(l, chosen, request.learnedCount())));
    }

    private void storeLinks(Lesson lesson, List<LinkChoice> links, long learnedCount) {
        lesson.setLinksJson(mapper.writeValueAsString(new Links(links)));
        lesson.setLinksAtLearnedCount(learnedCount);
    }

    /** Stored links, minus sections since removed or no longer learned. */
    private List<LinkDto> buildsOn(Lesson lesson) {
        if (lesson.getLinksJson() == null) {
            return List.of();
        }
        Links links = mapper.readValue(lesson.getLinksJson(), Links.class);
        List<LinkDto> out = new ArrayList<>();
        for (LinkChoice c : links.links() == null ? List.<LinkChoice>of() : links.links()) {
            mapItems.findById(c.mapItemId())
                    .filter(m -> !m.isHidden() && m.isLearned())
                    .ifPresent(m -> out.add(new LinkDto(m.getId(), m.getTitle(), m.getChapter().getResource().getTitle(), c.why())));
        }
        return out;
    }

    private List<QuestionView> quickCheck(MapItem item, LessonContent content, String bookTitle, String chapterTitle) {
        List<Point> sorted = points.findByMapItemId(item.getId()).stream()
                .sorted(Comparator.comparingInt(Point::getFirstPage).thenComparing(Point::getId))
                .toList();
        Set<Long> designed = content.miniDesign() == null ? Set.of() : Set.copyOf(content.miniDesign().pointIds());
        List<Point> pool = sorted.stream().filter(p -> !designed.contains(p.getId())).toList();
        if (pool.isEmpty()) {
            pool = sorted;
        }
        List<Question> first = new ArrayList<>();
        for (Point p : pool) {
            Optional<Question> q = questions.findByPointIdAndRetiredFalseOrderByLastAskedAtAscIdAsc(p.getId()).stream().findFirst();
            q.ifPresent(first::add);
        }
        int n = content.miniDesign() == null ? QUICK_CHECK : QUICK_CHECK - 1;
        Set<Integer> picks = new LinkedHashSet<>();
        for (int i = 0; i < n && !first.isEmpty(); i++) {
            picks.add(i * first.size() / n);
        }
        return picks.stream()
                .map(first::get)
                .map(q -> new QuestionView(q.getId(), q.getType(), q.getPrompt(), q.getPoint().getId(), bookTitle, chapterTitle))
                .toList();
    }
}

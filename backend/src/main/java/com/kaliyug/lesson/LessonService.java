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
import com.kaliyug.map.QueueService;
import com.kaliyug.map.QueueService.QueueItemDto;
import com.kaliyug.revision.RevisionService.QuestionView;

/** Opens lessons, prepares upcoming ones in the background, and picks each lesson's quick check. */
@Service
public class LessonService {

    static final int PREPARE_AHEAD = 2;
    static final int QUICK_CHECK = 3;
    static final int MINI_DESIGN_MINUTES = 3;
    static final String NOT_IN_SYLLABUS = "This section isn't in your syllabus.";
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
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private Executor executor;

    public LessonService(MapItemRepository mapItems, LessonRepository lessons, PointRepository points,
            QuestionRepository questions, QueueService queue, LessonGenerator generator, ObjectMapper mapper,
            TransactionTemplate tx, Clock clock, @Qualifier("lessonExecutor") Executor lessonExecutor) {
        this.mapItems = mapItems;
        this.lessons = lessons;
        this.points = points;
        this.questions = questions;
        this.queue = queue;
        this.generator = generator;
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

    /** Filled in by the "Builds on" step. */
    private List<LinkDto> buildsOn(Lesson lesson) {
        return List.of();
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

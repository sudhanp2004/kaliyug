package com.kaliyug.map;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.ChapterRepository;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.revision.Scheduler;

/**
 * The "Up next" map: each book in order, rotating between books one chapter at a time.
 */
@Service
public class QueueService {

    /**
     * @param skipped   sections skipped and not yet back in the list
     * @param allPaused there are books, but learning is switched off for all of them
     */
    public record QueueDto(List<QueueItemDto> items, int remaining, boolean preparing, int skipped, boolean allPaused) {
    }

    /** A skipped section comes back to the top after this many more sections are marked learned. */
    static final int SKIP_RETURNS_AFTER = 10;

    public record QueueItemDto(Long id, String title, String bookTitle, String chapterTitle, int startPage, int endPage) {

        static QueueItemDto of(MapItem item) {
            Chapter c = item.getChapter();
            return new QueueItemDto(item.getId(), item.getTitle(), c.getResource().getTitle(), c.getTitle(),
                    item.getStartPage(), item.getEndPage());
        }
    }

    /** A book's remaining items, grouped by chapter, plus what decides its turn in the rotation. */
    private record BookQueue(Resource book, Deque<List<MapItem>> chapters, boolean midChapter, Instant lastStudied,
            List<MapItem> skipped) {
    }

    private final ResourceRepository resources;
    private final ChapterRepository chapters;
    private final MapItemRepository mapItems;
    private final PointRepository points;
    private final ProblemRepository problems;
    private final Scheduler scheduler;
    private final Clock clock;

    public QueueService(ResourceRepository resources, ChapterRepository chapters, MapItemRepository mapItems,
            PointRepository points, ProblemRepository problems, Scheduler scheduler, Clock clock) {
        this.resources = resources;
        this.chapters = chapters;
        this.mapItems = mapItems;
        this.points = points;
        this.problems = problems;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QueueDto queue(int limit) {
        long learnedCount = mapItems.countByLearnedAtIsNotNull();
        List<Resource> all = resources.findAll();
        List<BookQueue> books = new ArrayList<>();
        int skippedInPausedBooks = 0;
        for (Resource book : all) {
            BookQueue b = bookQueue(book);
            if (book.isLearningOn()) {
                books.add(b);
            } else {
                skippedInPausedBooks += b.skipped().size();
            }
        }
        books.sort(Comparator.comparing((BookQueue b) -> !b.midChapter())
                .thenComparing(BookQueue::lastStudied, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(b -> b.book().getId()));

        List<MapItem> ordered = new ArrayList<>();
        boolean added = true;
        while (added) {
            added = false;
            for (BookQueue b : books) {
                List<MapItem> next = b.chapters().poll();
                if (next != null) {
                    ordered.addAll(next);
                    added = true;
                }
            }
        }

        // Skipped sections come back on top once enough others are learned, or straight away when nothing else is left.
        Comparator<MapItem> bySkipOrder = Comparator.comparing(MapItem::getSkippedAtLearnedCount).thenComparing(MapItem::getId);
        List<MapItem> skipped = books.stream().flatMap(b -> b.skipped().stream()).sorted(bySkipOrder).toList();
        List<MapItem> back = ordered.isEmpty() ? skipped
                : skipped.stream().filter(i -> learnedCount - i.getSkippedAtLearnedCount() >= SKIP_RETURNS_AFTER).toList();
        List<MapItem> shown = new ArrayList<>(back);
        shown.addAll(ordered);

        boolean preparing = !resources.findByStatus(ResourceStatus.INGESTING).isEmpty();
        boolean allPaused = !all.isEmpty() && all.stream().noneMatch(Resource::isLearningOn);
        int stillSkipped = skipped.size() - back.size() + skippedInPausedBooks;
        List<QueueItemDto> items = shown.stream().limit(limit).map(QueueItemDto::of).toList();
        return new QueueDto(items, shown.size(), preparing, stillSkipped, allPaused);
    }

    private BookQueue bookQueue(Resource book) {
        Deque<List<MapItem>> remaining = new ArrayDeque<>();
        List<MapItem> skipped = new ArrayList<>();
        boolean midChapter = false;
        Instant lastStudied = null;
        boolean blocked = false;
        for (Chapter chapter : chapters.findByResourceIdOrderByOrderIndex(book.getId())) {
            if (chapter.getKind() == ChapterKind.FILLER) {
                continue;
            }
            List<MapItem> visible = mapItems.findByChapterIdOrderByOrderIndex(chapter.getId()).stream()
                    .filter(i -> !i.isHidden())
                    .toList();
            for (MapItem i : visible) {
                if (i.isLearned() && (lastStudied == null || i.getLearnedAt().isAfter(lastStudied))) {
                    lastStudied = i.getLearnedAt();
                }
            }
            visible.stream().filter(MapItem::isSkipped).forEach(skipped::add);
            if (blocked) {
                continue;
            }
            if (!chapter.isSectionsSplit()) {
                blocked = true;
                continue;
            }
            List<MapItem> todo = new ArrayList<>();
            for (MapItem i : visible) {
                if (i.isLearned() || i.isSkipped()) {
                    continue;
                }
                if (!i.isIngested()) {
                    blocked = true;
                    break;
                }
                todo.add(i);
            }
            if (!todo.isEmpty()) {
                remaining.add(todo);
            }
            if (visible.stream().anyMatch(MapItem::isLearned) && visible.stream().anyMatch(i -> !i.isLearned())) {
                midChapter = true;
            }
        }
        return new BookQueue(book, remaining, midChapter, lastStudied, skipped);
    }

    @Transactional
    public void markLearned(Long itemId) {
        MapItem item = find(itemId);
        if (item.isLearned()) {
            return;
        }
        Instant now = clock.instant();
        item.setLearnedAt(now);
        item.setSkippedAtLearnedCount(null);
        for (Point p : points.findByMapItemId(itemId)) {
            scheduler.onLearned(p);
        }
        scheduleProblemIfChapterDone(item.getChapter().getId());
    }

    /** Not now: the section leaves Up next and comes back after {@link #SKIP_RETURNS_AFTER} more are learned. */
    @Transactional
    public void skip(Long itemId) {
        MapItem item = find(itemId);
        if (!item.isLearned()) {
            item.setSkippedAtLearnedCount((int) mapItems.countByLearnedAtIsNotNull());
        }
    }

    @Transactional
    public void setHidden(Long itemId, boolean hidden) {
        MapItem item = find(itemId);
        item.setHidden(hidden);
        if (hidden) {
            scheduleProblemIfChapterDone(item.getChapter().getId());
        }
    }

    /** A chapter's design problem first comes due a day after every visible item of the chapter is learned. */
    @Transactional
    public void scheduleProblemIfChapterDone(Long chapterId) {
        List<MapItem> visible = mapItems.findByChapterIdOrderByOrderIndex(chapterId).stream()
                .filter(i -> !i.isHidden())
                .toList();
        if (visible.isEmpty() || !visible.stream().allMatch(MapItem::isLearned)) {
            return;
        }
        problems.findByChapterId(chapterId)
                .filter(p -> p.getDueAt() == null)
                .ifPresent(p -> p.setDueAt(clock.instant().plus(Duration.ofDays(1))));
    }

    @Transactional(readOnly = true)
    public List<QueueItemDto> hidden() {
        return mapItems.findByHiddenTrue().stream().map(QueueItemDto::of).toList();
    }

    private MapItem find(Long itemId) {
        return mapItems.findById(Objects.requireNonNull(itemId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such map item"));
    }
}

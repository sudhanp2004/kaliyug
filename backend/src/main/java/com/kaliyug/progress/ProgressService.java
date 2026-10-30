package com.kaliyug.progress;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kaliyug.domain.Attempt;
import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.domain.ResourceStatus;
import com.kaliyug.domain.Verdict;

/** How much of the syllabus is known well. Only wrong answers lower it; being overdue does not. */
@Service
public class ProgressService {

    public record ProgressDto(Bar overall, List<BookBar> books, Wins wins, long dueCount, boolean preparing) {
    }

    public record Bar(long knownWell, long total, int percent) {

        static Bar of(long knownWell, long total) {
            return new Bar(knownWell, total, total == 0 ? 0 : (int) Math.floor(100.0 * knownWell / total));
        }
    }

    /** mastered: the book has counted points and every one is known well. */
    public record BookBar(Long resourceId, String title, ResourceStatus status, String failureReason, Bar bar,
            boolean mastered) {
    }

    public record Wins(long weakPointsFixedThisWeek, List<String> solidChapters) {
    }

    private final PointRepository points;
    private final AttemptRepository attempts;
    private final ResourceRepository resources;
    private final Clock clock;

    public ProgressService(PointRepository points, AttemptRepository attempts, ResourceRepository resources, Clock clock) {
        this.points = points;
        this.attempts = attempts;
        this.resources = resources;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProgressDto progress() {
        Instant now = clock.instant();
        List<Point> counted = points.findCounted();

        Map<Long, long[]> perBook = new LinkedHashMap<>();
        Map<Chapter, long[]> perChapter = new LinkedHashMap<>();
        for (Point p : counted) {
            Chapter chapter = p.getMapItem().getChapter();
            tally(perBook.computeIfAbsent(chapter.getResource().getId(), k -> new long[2]), p);
            tally(perChapter.computeIfAbsent(chapter, k -> new long[2]), p);
        }

        List<BookBar> books = new ArrayList<>();
        for (Resource r : resources.findAll()) {
            long[] t = perBook.getOrDefault(r.getId(), new long[2]);
            books.add(new BookBar(r.getId(), r.getTitle(), r.getStatus(), r.getFailureReason(), Bar.of(t[0], t[1]),
                    t[1] > 0 && t[0] == t[1]));
        }

        List<String> solid = perChapter.entrySet().stream()
                .filter(e -> e.getValue()[1] > 0 && e.getValue()[0] == e.getValue()[1])
                .sorted((x, y) -> Integer.compare(x.getKey().getOrderIndex(), y.getKey().getOrderIndex()))
                .map(e -> e.getKey().getTitle())
                .toList();

        long known = counted.stream().filter(Point::isKnownWell).count();
        boolean preparing = !resources.findByStatus(ResourceStatus.INGESTING).isEmpty();
        return new ProgressDto(Bar.of(known, counted.size()), books,
                new Wins(weakPointsFixed(now.minus(Duration.ofDays(7))), solid),
                points.findDue(now).size(), preparing);
    }

    private static void tally(long[] t, Point p) {
        if (p.isKnownWell()) {
            t[0]++;
        }
        t[1]++;
    }

    private long weakPointsFixed(Instant since) {
        Set<Long> fixed = new HashSet<>();
        for (Attempt a : attempts.findByAtAfterOrderByAt(since.minusMillis(1))) {
            if (a.getVerdict() != Verdict.CORRECT) {
                continue;
            }
            Long pointId = a.getPoint().getId();
            attempts.findFirstByPointIdAndAtBeforeOrderByAtDesc(pointId, a.getAt())
                    .filter(prev -> prev.getVerdict() != Verdict.CORRECT)
                    .ifPresent(prev -> fixed.add(pointId));
        }
        return fixed.size();
    }
}

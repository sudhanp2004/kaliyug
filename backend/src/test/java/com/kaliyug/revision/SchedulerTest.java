package com.kaliyug.revision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private final Scheduler scheduler = new Scheduler(Clock.fixed(NOW, ZoneOffset.UTC));

    private static Point point(PointKind kind, int box, boolean knownWell) {
        Point p = new Point(null, kind, "t", 1, 1, "e");
        p.setBox(box);
        p.setKnownWell(knownWell);
        return p;
    }

    @Test
    void correctMovesUpOneBox() {
        Point p = point(PointKind.FACT, 2, false);
        scheduler.apply(p, Verdict.CORRECT);
        assertEquals(3, p.getBox());
        assertEquals(Instant.parse("2026-10-24T00:00:00Z"), p.getDueAt());
        assertTrue(p.isKnownWell());
    }

    @Test
    void numbersUseTheShorterLadder() {
        Point p = point(PointKind.NUMBER, 2, false);
        scheduler.apply(p, Verdict.CORRECT);
        assertEquals(3, p.getBox());
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"), p.getDueAt());
    }

    @Test
    void topBoxStaysAndWaitsSixtyDays() {
        Point p = point(PointKind.CONCEPT, 6, true);
        scheduler.apply(p, Verdict.CORRECT);
        assertEquals(6, p.getBox());
        assertEquals(NOW.plus(Duration.ofDays(60)), p.getDueAt());
    }

    @Test
    void partialDropsToBoxOneTomorrow() {
        Point p = point(PointKind.CONCEPT, 4, true);
        scheduler.apply(p, Verdict.PARTIAL);
        assertEquals(1, p.getBox());
        assertEquals(NOW.plus(Duration.ofDays(1)), p.getDueAt());
        assertFalse(p.isKnownWell());
    }

    @Test
    void wrongComesBackInTenMinutes() {
        Point p = point(PointKind.CONCEPT, 4, true);
        scheduler.apply(p, Verdict.WRONG);
        assertEquals(0, p.getBox());
        assertEquals(Instant.parse("2026-10-17T00:10:00Z"), p.getDueAt());
        assertFalse(p.isKnownWell());
    }

    @Test
    void learnedPointIsDueNow() {
        Point p = point(PointKind.CONCEPT, 3, false);
        scheduler.onLearned(p);
        assertEquals(0, p.getBox());
        assertEquals(NOW, p.getDueAt());
    }

    @Test
    void problemScheduling() {
        Problem fresh = new Problem(null, "t", "[]", "{}");
        scheduler.applyProblem(fresh, true);
        assertEquals(1, fresh.getBox());
        assertEquals(NOW.plus(Duration.ofDays(1)), fresh.getDueAt());

        Problem advanced = new Problem(null, "t", "[]", "{}");
        advanced.setBox(3);
        scheduler.applyProblem(advanced, false);
        assertEquals(1, advanced.getBox());
        assertEquals(NOW.plus(Duration.ofDays(1)), advanced.getDueAt());
    }
}

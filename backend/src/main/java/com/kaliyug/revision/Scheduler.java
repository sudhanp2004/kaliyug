package com.kaliyug.revision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.Verdict;

/** Leitner-style spaced repetition: each correct answer moves a point to a box that waits longer. */
@Component
public class Scheduler {

    static final int[] NORMAL_DAYS = {0, 1, 3, 7, 14, 30, 60};
    static final int[] QUICKFIRE_DAYS = {0, 1, 2, 4, 7, 14, 30};
    static final int MAX_BOX = 6;
    static final Duration WRONG_RETRY = Duration.ofMinutes(10);

    private final Clock clock;

    public Scheduler(Clock clock) {
        this.clock = clock;
    }

    public void onLearned(Point p) {
        p.setBox(0);
        p.setDueAt(clock.instant());
    }

    public void apply(Point p, Verdict verdict) {
        Instant now = clock.instant();
        switch (verdict) {
            case CORRECT -> {
                int box = Math.min(p.getBox() + 1, MAX_BOX);
                p.setBox(box);
                p.setDueAt(now.plus(Duration.ofDays(ladder(p)[box])));
                p.setKnownWell(true);
            }
            case PARTIAL -> {
                p.setBox(1);
                p.setDueAt(now.plus(Duration.ofDays(1)));
                p.setKnownWell(false);
            }
            case WRONG -> {
                p.setBox(0);
                p.setDueAt(now.plus(WRONG_RETRY));
                p.setKnownWell(false);
            }
        }
    }

    public void applyProblem(Problem problem, boolean allCovered) {
        Instant now = clock.instant();
        if (allCovered) {
            int box = Math.min(problem.getBox() + 1, MAX_BOX);
            problem.setBox(box);
            problem.setDueAt(now.plus(Duration.ofDays(NORMAL_DAYS[box])));
        } else {
            problem.setBox(1);
            problem.setDueAt(now.plus(Duration.ofDays(1)));
        }
    }

    private static int[] ladder(Point p) {
        return p.getKind() == PointKind.NUMBER ? QUICKFIRE_DAYS : NORMAL_DAYS;
    }
}

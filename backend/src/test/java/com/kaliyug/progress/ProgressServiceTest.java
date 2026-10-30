package com.kaliyug.progress;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kaliyug.domain.Attempt;
import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.Verdict;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.progress.ProgressService.Bar;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import({TestLlmConfig.class, Fixtures.class})
class ProgressServiceTest {

    @Autowired ProgressService progress;
    @Autowired Fixtures fx;
    @Autowired AttemptRepository attempts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.kaliyug.domain.ResourceRepository resources;

    private final Instant now = Instant.now();
    private Point a;
    private Point b;
    private Chapter solid;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        Resource book = fx.book("System Design Interview");
        solid = fx.chapter(book, 1, "Chapter 1", ChapterKind.CONCEPT);
        Chapter other = fx.chapter(book, 2, "Chapter 2", ChapterKind.DESIGN);
        Chapter filler = fx.chapter(book, 3, "Afterword", ChapterKind.FILLER);

        MapItem i1 = fx.item(solid, 0, "One");
        MapItem i2 = fx.item(other, 0, "Two");
        MapItem hidden = fx.item(other, 1, "Hidden");
        hidden.setHidden(true);
        fx.save(hidden);

        a = known(fx.point(i1, PointKind.FACT, "a"), true);
        b = known(fx.point(i1, PointKind.FACT, "b"), true);
        b.setDueAt(now.minus(Duration.ofDays(30)));
        fx.save(b);
        fx.point(i2, PointKind.FACT, "c");
        fx.point(i2, PointKind.FACT, "d");
        known(fx.point(hidden, PointKind.FACT, "hidden"), true);
        known(fx.point(fx.item(filler, 0, "Filler"), PointKind.FACT, "filler"), true);
    }

    private Point known(Point p, boolean knownWell) {
        p.setKnownWell(knownWell);
        return fx.save(p);
    }

    private void attempt(Point p, Verdict v, Duration ago) {
        attempts.save(new Attempt(p, null, null, v, "answer", now.minus(ago)));
    }

    @Test
    void overallCountsOnlyVisibleNonFillerPointsAndIgnoresOverdue() {
        var dto = progress.progress();

        assertEquals(new Bar(2, 4, 50), dto.overall());
        assertEquals(1, dto.books().size());
        assertEquals(new Bar(2, 4, 50), dto.books().get(0).bar());
    }

    @Test
    void weakPointsFixedCountsCorrectAfterWrongThisWeek() {
        attempt(a, Verdict.WRONG, Duration.ofDays(3));
        attempt(a, Verdict.CORRECT, Duration.ofDays(1));
        attempt(b, Verdict.CORRECT, Duration.ofDays(1));

        assertEquals(1, progress.progress().wins().weakPointsFixedThisWeek());
    }

    @Test
    void chapterWithAllPointsKnownIsSolid() {
        assertEquals(List.of("Chapter 1"), progress.progress().wins().solidChapters());
    }

    @Test
    void dueCountSkipsBooksWithRevisionOff() {
        a.setDueAt(Instant.now().minus(Duration.ofHours(1)));
        fx.save(a);
        jdbc.update("update map_item set learned_at = ? where id = (select map_item_id from point where id = ?)",
                java.sql.Timestamp.from(Instant.now()), a.getId());
        assertEquals(2, progress.progress().dueCount(), "a and b share the learned section and are both overdue");

        var book = resources.findAll().get(0);
        book.setRevisionOn(false);
        resources.save(book);

        assertEquals(0, progress.progress().dueCount());
        assertEquals(4, progress.progress().overall().total(), "paused books still count towards progress");
    }

    @Test
    void bookIsMasteredOnlyWhenEveryCountedPointIsKnownWell() {
        MapItem ia = fx.item(fx.chapter(fx.book("A"), 1, "A1", ChapterKind.CONCEPT), 0, "a");
        known(fx.point(ia, PointKind.FACT, "a1"), true);
        known(fx.point(ia, PointKind.FACT, "a2"), true);
        MapItem ib = fx.item(fx.chapter(fx.book("B"), 1, "B1", ChapterKind.CONCEPT), 0, "b");
        known(fx.point(ib, PointKind.FACT, "b1"), true);
        known(fx.point(ib, PointKind.FACT, "b2"), false);
        fx.book("C");
        java.util.Map<String, Boolean> mastered = new java.util.HashMap<>();
        progress.progress().books().stream()
                .filter(bb -> List.of("A", "B", "C").contains(bb.title()))
                .forEach(bb -> mastered.put(bb.title(), bb.mastered()));
        assertEquals(java.util.Map.of("A", true, "B", false, "C", false), mastered);
    }
}

package com.kaliyug.domain;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class DomainMappingTest {

    @Autowired
    TestEntityManager em;
    @Autowired
    PointRepository points;
    @Autowired
    ProblemRepository problems;
    @Autowired
    QuestionRepository questions;

    private final Instant now = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void dueQueryReturnsOnlyLearnedVisiblePointsAndProblemRoundTrips() {
        Resource book = em.persist(new Resource("System Design Interview", "data/books/x.pdf", "sha-1", now));
        Chapter chapter = em.persist(new Chapter(book, 0, "CHAPTER 8: DESIGN A URL SHORTENER", 119, 131, ChapterKind.DESIGN));
        MapItem visible = new MapItem(chapter, 0, "Base62", 119, 124);
        visible.setIngested(true);
        visible.setLearnedAt(now.minusSeconds(3600));
        em.persist(visible);
        MapItem hidden = new MapItem(chapter, 1, "Hidden", 125, 131);
        hidden.setLearnedAt(now.minusSeconds(3600));
        hidden.setHidden(true);
        em.persist(hidden);

        Point due = new Point(visible, PointKind.CONCEPT, "Base62 conversion", 120, 120, "evidence text here ok");
        due.setDueAt(now.minusSeconds(60));
        em.persist(due);
        Point hiddenPoint = new Point(hidden, PointKind.FACT, "Hidden fact", 126, 126, "evidence text here ok");
        hiddenPoint.setDueAt(now.minusSeconds(60));
        em.persist(hiddenPoint);
        em.persist(new Question(due, QuestionType.SHORT, "Why base62?", "Short codes."));

        Problem problem = new Problem(chapter, "Design a URL shortener", "[]", "{}");
        problem.setPoints(Set.of(due, hiddenPoint));
        em.persist(problem);
        em.flush();
        em.clear();

        assertEquals(1, points.findDue(now).size());
        assertEquals(due.getId(), points.findDue(now).get(0).getId());
        assertEquals(1, questions.findByPointIdAndRetiredFalseOrderByLastAskedAtAscIdAsc(due.getId()).size());
        assertEquals(2, problems.findByChapterId(chapter.getId()).orElseThrow().getPoints().size());
    }
}

package com.kaliyug.map;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.MapItemRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestLlmConfig.class, Fixtures.class})
class MapControllerTest {

    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired MapItemRepository mapItems;
    @Autowired PointRepository points;
    @Autowired ProblemRepository problems;

    private Chapter design;
    private MapItem first;
    private MapItem second;
    private Point point;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        Resource book = fx.book("System Design Interview");
        design = fx.chapter(book, 4, "CHAPTER 4: DESIGN A RATE LIMITER", ChapterKind.DESIGN);
        first = fx.item(design, 0, "Step 1");
        second = fx.item(design, 1, "Step 2");
        point = fx.point(first, PointKind.CONCEPT, "Token bucket");
    }

    private static boolean near(Instant actual, Instant expected) {
        return Duration.between(expected, actual).abs().toSeconds() < 30;
    }

    @Test
    void queueEndpointReturnsItems() throws Exception {
        mvc.perform(get("/api/queue").param("limit", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Step 1"))
                .andExpect(jsonPath("$.remaining").value(2));
    }

    @Test
    void learnedMakesPointsDueNow() throws Exception {
        mvc.perform(post("/api/map-items/{id}/learned", first.getId())).andExpect(status().isNoContent());

        assertTrue(mapItems.findById(first.getId()).orElseThrow().isLearned());
        Point p = points.findById(point.getId()).orElseThrow();
        assertEquals(0, p.getBox());
        assertTrue(near(p.getDueAt(), Instant.now()));
    }

    @Test
    void learnedLastItemOfDesignChapterSchedulesProblemTomorrow() throws Exception {
        Problem problem = problems.save(new Problem(design, "Design a rate limiter", "[]", "{}"));

        mvc.perform(post("/api/map-items/{id}/learned", first.getId())).andExpect(status().isNoContent());
        assertNull(problems.findById(problem.getId()).orElseThrow().getDueAt());

        mvc.perform(post("/api/map-items/{id}/learned", second.getId())).andExpect(status().isNoContent());
        Instant due = problems.findById(problem.getId()).orElseThrow().getDueAt();
        assertTrue(near(due, Instant.now().plus(Duration.ofDays(1))), "due " + due);
    }

    @Test
    void learnedTwiceIsIdempotent() throws Exception {
        mvc.perform(post("/api/map-items/{id}/learned", first.getId()));
        Instant learnedAt = mapItems.findById(first.getId()).orElseThrow().getLearnedAt();
        Point p = points.findById(point.getId()).orElseThrow();
        p.setBox(3);
        points.save(p);

        mvc.perform(post("/api/map-items/{id}/learned", first.getId())).andExpect(status().isNoContent());

        assertEquals(learnedAt, mapItems.findById(first.getId()).orElseThrow().getLearnedAt());
        assertEquals(3, points.findById(point.getId()).orElseThrow().getBox());
    }

    @Test
    void hideUnhideRoundTrip() throws Exception {
        mvc.perform(post("/api/map-items/{id}/hide", first.getId())).andExpect(status().isNoContent());
        assertTrue(mapItems.findById(first.getId()).orElseThrow().isHidden());
        mvc.perform(get("/api/map-items/hidden"))
                .andExpect(jsonPath("$[0].title").value("Step 1"))
                .andExpect(jsonPath("$[0].bookTitle").value("System Design Interview"));
        mvc.perform(get("/api/queue")).andExpect(jsonPath("$.items[0].title").value("Step 2"));

        mvc.perform(post("/api/map-items/{id}/unhide", first.getId())).andExpect(status().isNoContent());
        assertFalse(mapItems.findById(first.getId()).orElseThrow().isHidden());
    }

    @Test
    void unknownItemIs404() throws Exception {
        mvc.perform(post("/api/map-items/{id}/learned", 999999)).andExpect(status().isNotFound());
    }

    @Test
    void hidingLastUnlearnedItemSchedulesProblem() throws Exception {
        Problem problem = problems.save(new Problem(design, "Design a rate limiter", "[]", "{}"));
        mvc.perform(post("/api/map-items/{id}/learned", first.getId())).andExpect(status().isNoContent());

        mvc.perform(post("/api/map-items/{id}/hide", second.getId())).andExpect(status().isNoContent());

        Instant due = problems.findById(problem.getId()).orElseThrow().getDueAt();
        assertTrue(due != null && near(due, Instant.now().plus(Duration.ofDays(1))), "due " + due);
    }
}

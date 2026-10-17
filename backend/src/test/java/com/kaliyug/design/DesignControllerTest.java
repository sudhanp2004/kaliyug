package com.kaliyug.design;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;
import com.kaliyug.support.TestClockConfig;

import static com.kaliyug.support.TestClockConfig.NOW;
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
@Import({TestLlmConfig.class, Fixtures.class, TestClockConfig.class})
class DesignControllerTest {

    private static final String DESIGN = """
            {"nodes":[{"id":"n1","label":"Client"},{"id":"n2","label":"Load balancer"}],
             "edges":[{"from":"n1","to":"n2","label":"HTTP"}],
             "notes":"302 redirect so we can track clicks"}""";

    @Autowired MockMvc mvc;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PointRepository points;
    @Autowired ProblemRepository problems;
    @Autowired AttemptRepository attempts;

    private Point p1;
    private Point p2;
    private Point p3;
    private Problem problem;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        Chapter chapter = fx.chapter(fx.book("System Design Interview"), 8, "CHAPTER 8: DESIGN A URL SHORTENER", ChapterKind.DESIGN);
        MapItem item = fx.item(chapter, 0, "Deep dive");
        p1 = fx.point(item, PointKind.CONCEPT, "Base62 conversion");
        p2 = fx.point(item, PointKind.TRADEOFF, "301 vs 302 redirect");
        p3 = fx.point(item, PointKind.NUMBER, "7 characters");
        problem = new Problem(chapter, "Design a URL shortener", "[\"100M URLs per day\"]",
                "{\"components\":[{\"name\":\"Load balancer\",\"role\":\"spreads traffic\",\"pointIds\":[]}],\"decisions\":[]}");
        problem.setPoints(Set.of(p1, p2, p3));
        problem = problems.save(problem);
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/problems/{id}/submit", problem.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String grade(String covered, String missed) {
        return "{\"coveredPointIds\":[" + covered + "],\"missedPointIds\":[" + missed
                + "],\"feedback\":\"Good start (p. 120).\",\"differences\":\"\"}";
    }

    @Test
    void problemEndpointReturnsRequirements() throws Exception {
        mvc.perform(get("/api/problems/{id}", problem.getId()))
                .andExpect(jsonPath("$.title").value("Design a URL shortener"))
                .andExpect(jsonPath("$.requirements[0]").value("100M URLs per day"))
                .andExpect(jsonPath("$.chapterTitle").value("CHAPTER 8: DESIGN A URL SHORTENER"))
                .andExpect(jsonPath("$.bookTitle").value("System Design Interview"));
    }

    @Test
    void requirementsDropLeakedPointIds() throws Exception {
        // p2 and p3 belong to the chapter but are not linked to the problem; the AI still cites them.
        problem.setPoints(Set.of(p1));
        problems.save(problem);
        String json = "[\"Low latency (" + p1.getId() + ")\",\"Keys are unique (point " + p2.getId() + ").\","
                + "\"Spread load (points " + p1.getId() + ", " + p3.getId() + ")\",\"10,000 IDs (per second)\","
                + "\"Write QPS is about (1160)\",\"Store (100,000,000) URLs\"]";
        jdbc.update("update problem set requirements_json = ? where id = ?", json, problem.getId());

        mvc.perform(get("/api/problems/{id}", problem.getId()))
                .andExpect(jsonPath("$.requirements[0]").value("Low latency"))
                .andExpect(jsonPath("$.requirements[1]").value("Keys are unique."))
                .andExpect(jsonPath("$.requirements[2]").value("Spread load"))
                .andExpect(jsonPath("$.requirements[3]").value("10,000 IDs (per second)"))
                .andExpect(jsonPath("$.requirements[4]").value("Write QPS is about (1160)"))
                .andExpect(jsonPath("$.requirements[5]").value("Store (100,000,000) URLs"));
    }

    @Test
    void emptyCanvasIsRejected() throws Exception {
        submit("{\"nodes\":[],\"edges\":[],\"notes\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Add at least one box before submitting."));
        assertTrue(llm.calls.isEmpty());
    }

    @Test
    void submissionSentAsStructuredText() throws Exception {
        llm.reply(grade("", ""));

        submit(DESIGN).andExpect(status().isOk());

        String user = llm.calls.get(0)[1];
        assertTrue(user.contains("- Load balancer"), user);
        assertTrue(user.contains("- Client -> Load balancer (HTTP)"), user);
        assertTrue(user.contains("302 redirect so we can track clicks"));
        assertTrue(user.contains("100M URLs per day"));
    }

    @Test
    void coveredAndMissedPointsUpdateScheduleAndProblem() throws Exception {
        p3.setBox(2);
        fx.save(p3);
        llm.reply(grade(p1.getId() + ",999", String.valueOf(p2.getId())));

        submit(DESIGN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedback").value("Good start (p. 120)."))
                .andExpect(jsonPath("$.points.length()").value(2));

        assertTrue(points.findById(p1.getId()).orElseThrow().isKnownWell());
        Point after2 = points.findById(p2.getId()).orElseThrow();
        assertFalse(after2.isKnownWell());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), after2.getDueAt());
        Point after3 = points.findById(p3.getId()).orElseThrow();
        assertEquals(2, after3.getBox());
        assertNull(after3.getDueAt());
        assertEquals(2, attempts.count());
        Problem afterProblem = problems.findById(problem.getId()).orElseThrow();
        assertEquals(1, afterProblem.getBox());
        assertEquals(NOW.plus(Duration.ofDays(1)), afterProblem.getDueAt());
    }

    @Test
    void allCoveredAdvancesProblemBox() throws Exception {
        problem.setBox(2);
        problems.save(problem);
        llm.reply(grade(p1.getId() + "," + p2.getId() + "," + p3.getId(), ""));

        submit(DESIGN).andExpect(status().isOk());

        Problem after = problems.findById(problem.getId()).orElseThrow();
        assertEquals(3, after.getBox());
        assertEquals(NOW.plus(Duration.ofDays(7)), after.getDueAt());
    }

    @Test
    void outageReturns503AndChangesNothing() throws Exception {
        llm.fail(new LlmUnavailableException("down"));

        submit(DESIGN).andExpect(status().isServiceUnavailable());

        assertEquals(0, attempts.count());
        assertNull(problems.findById(problem.getId()).orElseThrow().getDueAt());
    }
}

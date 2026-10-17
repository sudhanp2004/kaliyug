package com.kaliyug.revision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;
import com.kaliyug.support.TestClockConfig;

import static com.kaliyug.support.TestClockConfig.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestLlmConfig.class, Fixtures.class, TestClockConfig.class})
class RevisionControllerTest {

    @Autowired MockMvc mvc;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PointRepository points;
    @Autowired QuestionRepository questions;
    @Autowired AttemptRepository attempts;
    @Autowired ProblemRepository problems;

    private Chapter chapter;
    private MapItem item;
    private Point point;
    private Question question;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        chapter = fx.chapter(fx.book("System Design Interview"), 4, "CHAPTER 4: DESIGN A RATE LIMITER", ChapterKind.DESIGN);
        item = fx.item(chapter, 0, "Token bucket");
        item.setLearnedAt(NOW.minus(Duration.ofDays(2)));
        fx.save(item);
        point = duePoint("Token bucket refills at a fixed rate");
        question = fx.question(point, QuestionType.SHORT, "How does a token bucket work?", "Tokens refill at a fixed rate.");
    }

    private Point duePoint(String text) {
        Point p = fx.point(item, PointKind.CONCEPT, text);
        p.setDueAt(NOW.minus(Duration.ofHours(1)));
        return fx.save(p);
    }

    private void answer(Long questionId, String text) throws Exception {
        mvc.perform(post("/api/questions/{id}/answer", questionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"" + text + "\"}"));
    }

    @Test
    void nextReturnsDueQuestionThenDone() throws Exception {
        mvc.perform(get("/api/revision/next").param("answered", "0"))
                .andExpect(jsonPath("$.type").value("QUESTION"))
                .andExpect(jsonPath("$.question.id").value(question.getId()))
                .andExpect(jsonPath("$.question.pointId").value(point.getId()))
                .andExpect(jsonPath("$.question.bookTitle").value("System Design Interview"))
                .andExpect(jsonPath("$.question.prompt").value("How does a token bucket work?"));

        mvc.perform(get("/api/revision/next").param("answered", "1").param("exclude", String.valueOf(point.getId())))
                .andExpect(jsonPath("$.type").value("DONE"));
    }

    @Test
    void wrongAnswerDropsKnownWellAndReschedules() throws Exception {
        point.setBox(3);
        point.setKnownWell(true);
        fx.save(point);
        llm.reply("{\"verdict\":\"WRONG\",\"feedback\":\"No.\",\"notInBook\":false}");

        mvc.perform(post("/api/questions/{id}/answer", question.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"no idea really\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("WRONG"))
                .andExpect(jsonPath("$.modelAnswer").value("Tokens refill at a fixed rate."))
                .andExpect(jsonPath("$.bookTitle").value("System Design Interview"))
                .andExpect(jsonPath("$.firstPage").value(point.getFirstPage()));

        Point after = points.findById(point.getId()).orElseThrow();
        assertFalse(after.isKnownWell());
        assertEquals(0, after.getBox());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), after.getDueAt());
    }

    @Test
    void gradingOutageReturns503AndLeavesPointUntouched() throws Exception {
        point.setBox(3);
        fx.save(point);
        llm.fail(new LlmUnavailableException("down"));

        mvc.perform(post("/api/questions/{id}/answer", question.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"something\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("AI is unreachable right now — your answer wasn't graded, try again."));

        Point after = points.findById(point.getId()).orElseThrow();
        assertEquals(3, after.getBox());
        assertEquals(0, attempts.count());
    }

    @Test
    void overrideRestoresAndAppliesCorrect() throws Exception {
        point.setBox(3);
        fx.save(point);
        llm.reply("{\"verdict\":\"WRONG\",\"feedback\":\"No.\",\"notInBook\":false}");
        answer(question.getId(), "my answer");
        Long attemptId = attempts.findAll().get(0).getId();

        mvc.perform(post("/api/attempts/{id}/override", attemptId)).andExpect(status().isNoContent());

        Point after = points.findById(point.getId()).orElseThrow();
        assertEquals(4, after.getBox());
        assertTrue(after.isKnownWell());
        assertTrue(attempts.findById(attemptId).orElseThrow().isOverridden());

        mvc.perform(post("/api/attempts/{id}/override", attemptId)).andExpect(status().isNoContent());
        assertEquals(4, points.findById(point.getId()).orElseThrow().getBox());
    }

    @Test
    void flaggedQuestionIsNeverServedAndReplacementIsGenerated() throws Exception {
        mvc.perform(post("/api/questions/{id}/flag", question.getId())).andExpect(status().isNoContent());
        llm.reply("{\"prompt\":\"What limits a token bucket?\",\"modelAnswer\":\"Its refill rate and size.\"}");

        mvc.perform(get("/api/revision/next").param("answered", "0"))
                .andExpect(jsonPath("$.type").value("QUESTION"))
                .andExpect(jsonPath("$.question.prompt").value("What limits a token bucket?"));

        assertEquals(1, llm.calls.size());
        assertEquals(2, questions.count());
        assertTrue(questions.findById(question.getId()).orElseThrow().isRetired());
    }

    @Test
    void problemServedEveryTenthAnswerOrWhenNoPointsLeft() throws Exception {
        List<Long> ids = new ArrayList<>(List.of(point.getId()));
        for (int i = 0; i < 19; i++) {
            Point p = duePoint("point " + i);
            fx.question(p, QuestionType.SHORT, "Q" + i, "A" + i);
            ids.add(p.getId());
        }
        Problem problem = new Problem(chapter, "Design a rate limiter", "[\"Limit requests per user\"]", "{}");
        problem.setDueAt(NOW.minus(Duration.ofHours(1)));
        problems.save(problem);

        mvc.perform(get("/api/revision/next").param("answered", "10"))
                .andExpect(jsonPath("$.type").value("PROBLEM"))
                .andExpect(jsonPath("$.problem.title").value("Design a rate limiter"))
                .andExpect(jsonPath("$.problem.requirements[0]").value("Limit requests per user"));
        mvc.perform(get("/api/revision/next").param("answered", "3"))
                .andExpect(jsonPath("$.type").value("QUESTION"));
        String all = String.join(",", ids.stream().map(String::valueOf).toList());
        mvc.perform(get("/api/revision/next").param("answered", "3").param("exclude", all))
                .andExpect(jsonPath("$.type").value("PROBLEM"));
    }

    @Test
    void notYetDuePointIsNotServed() throws Exception {
        point.setDueAt(NOW.plus(Duration.ofDays(1)));
        fx.save(point);

        mvc.perform(get("/api/revision/next").param("answered", "0"))
                .andExpect(jsonPath("$.type").value("DONE"));
    }
}

package com.kaliyug.revision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Verdict;
import com.kaliyug.llm.FakeLlm;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import({TestLlmConfig.class, Fixtures.class})
class AnswerGraderTest {

    @Autowired AnswerGrader grader;
    @Autowired FakeLlm llm;
    @Autowired Fixtures fx;
    @Autowired QuestionRepository questions;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private Question shortQ;
    private Question quickQ;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
        llm.reset();
        MapItem item = fx.item(fx.chapter(fx.book("Book"), 2, "CHAPTER 2", ChapterKind.CONCEPT), 0, "Latency");
        shortQ = fx.question(fx.point(item, PointKind.CONCEPT, "Why cache"), QuestionType.SHORT, "Why cache?", "Memory is faster than disk.");
        quickQ = fx.question(fx.point(item, PointKind.NUMBER, "Disk seek"), QuestionType.QUICKFIRE, "Disk seek time?", "10 ms");
    }

    private AnswerGrade grade(Question q, String answer) {
        return tx.execute(s -> grader.grade(questions.findById(q.getId()).orElseThrow(), answer));
    }

    @Test
    void blankAnswerIsWrongWithoutCallingAi() {
        AnswerGrade g = grade(shortQ, "   ");

        assertEquals(new AnswerGrade(Verdict.WRONG, "No answer given.", false), g);
        assertTrue(llm.calls.isEmpty());
    }

    @Test
    void quickfireExactMatchSkipsAi() {
        assertEquals(Verdict.CORRECT, grade(quickQ, " 10ms ").verdict());
        assertEquals(Verdict.CORRECT, grade(quickQ, "about 10 ms").verdict());
        assertTrue(llm.calls.isEmpty());
    }

    @Test
    void quickfireMismatchAsksAi() {
        llm.reply("{\"verdict\":\"WRONG\",\"feedback\":\"The book says 10 ms.\",\"notInBook\":false}");

        assertEquals(Verdict.WRONG, grade(quickQ, "100 ms").verdict());
        assertEquals(1, llm.calls.size());
    }

    @Test
    void shortAnswerUsesAiVerdict() {
        llm.reply("{\"verdict\":\"PARTIAL\",\"feedback\":\"Missed X\",\"notInBook\":false}");

        AnswerGrade g = grade(shortQ, "Because it is quick");

        assertEquals(new AnswerGrade(Verdict.PARTIAL, "Missed X", false), g);
        String user = llm.calls.get(0)[1];
        assertTrue(user.contains("Why cache?") && user.contains("Memory is faster than disk.") && user.contains("Because it is quick"));
    }
}

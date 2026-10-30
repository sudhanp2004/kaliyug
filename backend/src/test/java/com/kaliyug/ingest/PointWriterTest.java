package com.kaliyug.ingest;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.MapItem;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.ingest.AiContracts.ExtractedPoint;
import com.kaliyug.ingest.AiContracts.ExtractedQuestion;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Import({TestLlmConfig.class, Fixtures.class})
class PointWriterTest {

    @Autowired PointWriter writer;
    @Autowired Fixtures fx;
    @Autowired QuestionRepository questions;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
    }

    @Test
    void savesOnlyGroundedPointsThatHaveQuestions() {
        MapItem item = fx.item(fx.chapter(fx.book("B"), 1, "Ch", ChapterKind.CONCEPT), 0, "s"); // page 11
        String text = "A cache keeps frequently read data in memory for speed.";
        var q = new ExtractedQuestion(QuestionType.SHORT, "Why cache?", "Speed.");
        List<Point> saved = tx.execute(s -> writer.saveGrounded(item, List.of(
                new ExtractedPoint(PointKind.CONCEPT, "Caches speed reads", 11, 11, text, List.of(q)),
                new ExtractedPoint(PointKind.CONCEPT, "Invented", 11, 11, "This sentence is not in the book at all.", List.of(q)),
                new ExtractedPoint(PointKind.CONCEPT, "No questions", 11, 11, text, List.of()),
                new ExtractedPoint(PointKind.CONCEPT, "Wrong page", 40, 40, text, List.of(q))), text));
        assertEquals(List.of("Caches speed reads"), saved.stream().map(Point::getText).toList());
        assertEquals(1, questions.count());
    }
}

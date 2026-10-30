package com.kaliyug.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;
import com.kaliyug.support.Fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import({TestLlmConfig.class, Fixtures.class})
class LessonMappingTest {

    @Autowired LessonRepository lessons;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
    }

    @Test
    void lessonRoundTripsAndIsOnePerSection() {
        MapItem item = fx.item(fx.chapter(fx.book("B"), 1, "Ch", ChapterKind.CONCEPT), 0, "s");
        Lesson l = new Lesson(item);
        l.setContentJson("{}");
        l.setStatus(LessonStatus.READY);
        lessons.save(l);
        assertEquals(LessonStatus.READY, lessons.findByMapItemId(item.getId()).orElseThrow().getStatus());
        assertThrows(DataIntegrityViolationException.class, () -> lessons.saveAndFlush(new Lesson(item)));
        String type = jdbc.queryForObject(
                "select data_type from information_schema.columns where table_name = 'LESSON' and column_name = 'STATUS'",
                String.class);
        assertEquals("CHARACTER VARYING", type);
    }
}

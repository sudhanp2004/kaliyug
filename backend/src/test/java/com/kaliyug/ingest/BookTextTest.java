package com.kaliyug.ingest;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;
import com.kaliyug.domain.Resource;
import com.kaliyug.domain.ResourceRepository;
import com.kaliyug.llm.TestLlmConfig;
import com.kaliyug.support.Db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestLlmConfig.class)
class BookTextTest {

    @Autowired BookText bookText;
    @Autowired ResourceRepository resources;
    @Autowired PageTextRepository pages;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Db.wipe(jdbc);
    }

    @Test
    void chunksPackWholePagesAndKnowTheirPages() {
        Resource r = resources.save(new Resource("B", "x.pdf", "sha-bt", Instant.now()));
        for (int n = 1; n <= 3; n++) {
            pages.save(new PageText(r, n, "x".repeat(50)));
        }
        // each marked page is 15 + 50 = 65 chars; two pages + newline = 131
        List<BookText.Chunk> chunks = bookText.chunks(r.getId(), 1, 3, 140);
        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).firstPage());
        assertEquals(2, chunks.get(0).lastPage());
        assertEquals(3, chunks.get(1).firstPage());
        assertEquals(3, chunks.get(1).lastPage());
        assertTrue(chunks.get(0).text().contains("=== PAGE 2 ==="));
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 140));
    }
}

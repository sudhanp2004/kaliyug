package com.kaliyug.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.kaliyug.library.LibraryService;
import com.kaliyug.llm.KeyPool;
import com.kaliyug.llm.TestLlmConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The real config uses paths like ../.env and .. (relative to backend/); they must bind. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.env-file=../backend/src/test/resources/test.env",
        "app.books-dir=..",
        "app.data-dir=../backend/target/test-data"})
@Import(TestLlmConfig.class)
class ParentPathConfigTest {

    @Autowired KeyPool keys;
    @Autowired LibraryService library;

    @Test
    void parentRelativePathsBind() {
        assertEquals(2, keys.size());
        library.available();
    }
}

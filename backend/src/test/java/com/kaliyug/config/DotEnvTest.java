package com.kaliyug.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DotEnvTest {

    @Test
    void parsesKeysCommentsAndQuotes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "# c\nGROQ_API_KEY=a\nGROQ_API_KEY2=\"b\"\n\nHF_TOKEN='c'\n");

        assertEquals(Map.of("GROQ_API_KEY", "a", "GROQ_API_KEY2", "b", "HF_TOKEN", "c"), DotEnv.parse(file));
    }

    @Test
    void lastDuplicateWins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "A=1\nA=2\n");

        assertEquals(Map.of("A", "2"), DotEnv.parse(file));
    }
}

package com.kaliyug.config;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.KeyPool;
import com.kaliyug.llm.LlmClient;

@Configuration
public class AppConfig {

    private static final Pattern GROQ_KEY = Pattern.compile("^GROQ_API_KEY(\\d*)$");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    KeyPool keyPool(@Value("${app.env-file}") String envFile, Clock clock) {
        Path path = Path.of(envFile);
        return new KeyPool(groqKeys(DotEnv.parse(path), path), clock);
    }

    static List<String> groqKeys(Map<String, String> env, Path envFile) {
        List<String> keys = env.entrySet().stream()
                .filter(e -> GROQ_KEY.matcher(e.getKey()).matches() && !e.getValue().isBlank())
                .sorted(Comparator.comparingInt(e -> suffix(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
        if (keys.isEmpty()) {
            throw new IllegalStateException("No GROQ_API_KEY found in " + envFile);
        }
        return keys;
    }

    private static int suffix(String name) {
        Matcher m = GROQ_KEY.matcher(name);
        m.matches();
        return m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService ingestionExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ingestion");
            t.setDaemon(true);
            return t;
        });
    }

    /** Lessons get their own thread so they don't wait behind a book being read. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService lessonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lessons");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    JsonLlm jsonLlm(LlmClient llm, ObjectMapper mapper) {
        return new JsonLlm(llm, mapper);
    }
}

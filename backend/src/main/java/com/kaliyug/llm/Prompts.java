package com.kaliyug.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** System prompts stored as classpath:prompts/&lt;name&gt;.txt. */
@Component
public class Prompts {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String name) {
        return cache.computeIfAbsent(name, Prompts::load);
    }

    private static String load(String name) {
        try (InputStream in = Prompts.class.getResourceAsStream("/prompts/" + name + ".txt")) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt: prompts/" + name + ".txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.kaliyug.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal parser for KEY=value files. */
public final class DotEnv {

    private DotEnv() {
    }

    public static Map<String, String> parse(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                int eq = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).trim(), unquote(trimmed.substring(eq + 1).trim()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}

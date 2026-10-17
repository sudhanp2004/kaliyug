package com.kaliyug.design;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a problem's stored requirements, dropping point-id references the AI sometimes adds, like "(142)".
 * Only references made entirely of point ids from the problem's chapter are dropped, so numbers the book states stay.
 */
public final class Requirements {

    private static final Pattern POINT_REFERENCE = Pattern.compile("\\s*\\((?:points?\\s*)?(\\d+(?:\\s*,\\s*\\d+)*)\\)");

    private Requirements() {
    }

    public static List<String> read(ObjectMapper mapper, String json, Set<Long> pointIds) {
        List<String> raw = mapper.readValue(json, new TypeReference<List<String>>() {
        });
        return raw.stream().map(r -> clean(r, pointIds)).toList();
    }

    private static String clean(String requirement, Set<Long> pointIds) {
        Matcher m = POINT_REFERENCE.matcher(requirement);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            boolean allIds = Arrays.stream(m.group(1).split("\\s*,\\s*"))
                    .allMatch(id -> pointIds.contains(Long.parseLong(id)));
            m.appendReplacement(out, allIds ? "" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString().trim();
    }
}

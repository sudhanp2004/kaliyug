package com.kaliyug.design;

import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Reads a problem's stored requirements, dropping point-id references the AI sometimes adds, like "(142)". */
public final class Requirements {

    private static final Pattern POINT_REFERENCE = Pattern.compile("\\s*\\((?:points?\\s*)?\\d+(?:\\s*,\\s*\\d+)*\\)");

    private Requirements() {
    }

    public static List<String> read(ObjectMapper mapper, String json) {
        List<String> raw = mapper.readValue(json, new TypeReference<List<String>>() {
        });
        return raw.stream().map(r -> POINT_REFERENCE.matcher(r).replaceAll("").trim()).toList();
    }
}

package com.kaliyug.revision;

/** JSON shape the AI returns when asked for a fresh question about a point. */
public record NewQuestion(String prompt, String modelAnswer) {
}

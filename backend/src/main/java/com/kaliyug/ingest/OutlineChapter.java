package com.kaliyug.ingest;

/** A top-level bookmark with its 1-based, inclusive page range. */
public record OutlineChapter(String title, int startPage, int endPage) {
}

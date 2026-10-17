package com.kaliyug.domain;

/** Keeps AI- and PDF-supplied strings within their column sizes. */
public final class Text {

    public static final int TITLE = 255;
    public static final int REASON = 1000;

    private Text() {
    }

    public static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

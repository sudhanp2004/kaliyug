package com.kaliyug.ingest;

import java.util.List;
import java.util.Locale;

import com.kaliyug.ingest.AiContracts.Section;

/** Checks that keep AI output tied to the book. */
public final class Grounding {

    private static final int MIN_EVIDENCE_CHARS = 20;

    private Grounding() {
    }

    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** True when the quote is long enough to mean something and appears in the text, ignoring case and punctuation. */
    public static boolean evidenceFound(String evidence, String pagesText) {
        String quote = normalize(evidence);
        return quote.length() >= MIN_EVIDENCE_CHARS && normalize(pagesText).contains(quote);
    }

    /** True when the sections cover the chapter from first to last page without gaps (sharing a page is fine). */
    public static boolean validSections(List<Section> sections, int chapterStart, int chapterEnd) {
        if (sections == null || sections.isEmpty()) {
            return false;
        }
        if (sections.get(0).startPage() != chapterStart || sections.get(sections.size() - 1).endPage() != chapterEnd) {
            return false;
        }
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.startPage() > s.endPage()) {
                return false;
            }
            if (i > 0) {
                int prevEnd = sections.get(i - 1).endPage();
                if (s.startPage() != prevEnd && s.startPage() != prevEnd + 1) {
                    return false;
                }
            }
        }
        return true;
    }
}

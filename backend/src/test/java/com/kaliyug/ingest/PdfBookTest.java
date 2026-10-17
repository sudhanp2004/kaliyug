package com.kaliyug.ingest;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses the real books in the project root; skipped where they are absent. */
class PdfBookTest {

    private static final Path ALEX_XU = Path.of("../System Design Interview by Alex Xu.pdf");
    private static final Path WEB_SCALABILITY = Path.of("../Web Scalability For Startup Engineers PDF.pdf");

    @Test
    void alexXuChapters() throws Exception {
        assumeTrue(Files.exists(ALEX_XU));
        try (var book = PdfBook.open(ALEX_XU)) {
            assertEquals(269, book.pageCount());
            var chapters = book.chapters();
            assertEquals(19, chapters.size());
            assertEquals(new OutlineChapter("CHAPTER 8: DESIGN A URL SHORTENER", 119, 131), chapters.get(9));
            assertEquals("AFTERWORD", chapters.get(18).title());
            assertEquals(269, chapters.get(18).endPage());
            assertTrue(book.pageText(119).contains("URL SHORTENER"));
        }
    }

    @Test
    void pdfWithoutOutlineIsRejected() throws Exception {
        assumeTrue(Files.exists(WEB_SCALABILITY));
        try (var book = PdfBook.open(WEB_SCALABILITY)) {
            var e = assertThrows(IllegalArgumentException.class, book::chapters);
            assertTrue(e.getMessage().startsWith("This PDF has no bookmarks"));
        }
    }
}

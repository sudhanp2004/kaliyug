package com.kaliyug.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/** Builds small PDFs for tests. */
public final class TestPdfs {

    public static final String PAGE_SENTENCE = "The load balancer evenly distributes incoming traffic across servers. Value is 42 ms.";

    private TestPdfs() {
    }

    /** 6 pages; bookmarks Foreword -> 1, Chapter 1: Basics -> 2, Chapter 2: Design X -> 4. */
    public static Path outlined(Path file) {
        return write(file, "Fixture Book", true);
    }

    /** 3 pages and no bookmarks. */
    public static Path withoutBookmarks(Path file) {
        return write(file, "No Bookmarks", false);
    }

    private static Path write(Path file, String title, boolean outlined) {
        int pages = outlined ? 6 : 3;
        try (PDDocument doc = new PDDocument()) {
            doc.getDocumentInformation().setTitle(title);
            for (int n = 1; n <= pages; n++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                    out.beginText();
                    out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    out.newLineAtOffset(40, 700);
                    out.showText("Page " + n + ". " + PAGE_SENTENCE);
                    out.endText();
                }
            }
            if (outlined) {
                PDDocumentOutline outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);
                bookmark(doc, outline, "Foreword", 1);
                bookmark(doc, outline, "Chapter 1: Basics", 2);
                bookmark(doc, outline, "Chapter 2: Design X", 4);
            }
            doc.save(file.toFile());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void bookmark(PDDocument doc, PDDocumentOutline outline, String title, int page) {
        PDPageFitDestination dest = new PDPageFitDestination();
        dest.setPage(doc.getPage(page - 1));
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        item.setDestination(dest);
        outline.addLast(item);
    }
}

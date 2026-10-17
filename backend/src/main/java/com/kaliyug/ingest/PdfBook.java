package com.kaliyug.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

/** Page text and bookmark-based chapters of a PDF. Page numbers are 1-based PDF page indexes. */
public class PdfBook implements AutoCloseable {

    static final String NO_BOOKMARKS = "This PDF has no bookmarks (table of contents), so chapters can't be detected yet.";

    private final PDDocument document;

    private PdfBook(PDDocument document) {
        this.document = document;
    }

    public static PdfBook open(Path path) {
        try {
            return new PdfBook(Loader.loadPDF(path.toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open PDF " + path.getFileName(), e);
        }
    }

    public int pageCount() {
        return document.getNumberOfPages();
    }

    public String title() {
        String title = document.getDocumentInformation().getTitle();
        return title == null || title.isBlank() ? null : title.trim();
    }

    public String pageText(int pageNumber) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            return stripper.getText(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read page " + pageNumber, e);
        }
    }

    public List<OutlineChapter> chapters() {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        List<String> titles = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        if (outline != null) {
            for (PDOutlineItem item : outline.children()) {
                Integer start = startPage(item);
                if (start != null) {
                    titles.add(item.getTitle() == null ? "Untitled" : item.getTitle().trim());
                    starts.add(start);
                }
            }
        }
        if (titles.isEmpty()) {
            throw new IllegalArgumentException(NO_BOOKMARKS);
        }
        List<OutlineChapter> chapters = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : pageCount();
            chapters.add(new OutlineChapter(titles.get(i), start, Math.max(start, end)));
        }
        return chapters;
    }

    private Integer startPage(PDOutlineItem item) {
        try {
            PDPage page = item.findDestinationPage(document);
            if (page == null) {
                return null;
            }
            int index = document.getPages().indexOf(page);
            return index < 0 ? null : index + 1;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            document.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

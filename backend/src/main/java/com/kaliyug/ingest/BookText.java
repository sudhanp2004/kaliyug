package com.kaliyug.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;

/** The stored text of a book's pages: plain, marked with page numbers, or packed into request-sized chunks. */
@Component
public class BookText {

    /** A run of whole pages that fits one AI request. */
    public record Chunk(String text, int firstPage, int lastPage) {
    }

    private final PageTextRepository pages;

    public BookText(PageTextRepository pages) {
        this.pages = pages;
    }

    public String plain(Long resourceId, int from, int to) {
        return load(resourceId, from, to).stream().map(PageText::getText).collect(Collectors.joining("\n"));
    }

    public List<String> marked(Long resourceId, int from, int to) {
        return load(resourceId, from, to).stream().map(BookText::mark).toList();
    }

    /** Packs whole pages into chunks of at most {@code budget} characters; a page longer than the budget is cut. */
    public List<Chunk> chunks(Long resourceId, int from, int to, int budget) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int first = -1;
        int last = -1;
        for (PageText page : load(resourceId, from, to)) {
            String marked = mark(page);
            String fitted = marked.length() > budget ? marked.substring(0, budget) : marked;
            if (current.length() > 0 && current.length() + 1 + fitted.length() > budget) {
                chunks.add(new Chunk(current.toString(), first, last));
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            } else {
                first = page.getPageNumber();
            }
            current.append(fitted);
            last = page.getPageNumber();
        }
        if (current.length() > 0) {
            chunks.add(new Chunk(current.toString(), first, last));
        }
        return chunks;
    }

    private List<PageText> load(Long resourceId, int from, int to) {
        return pages.findByResourceIdAndPageNumberBetweenOrderByPageNumber(resourceId, from, to);
    }

    private static String mark(PageText p) {
        return "=== PAGE " + p.getPageNumber() + " ===\n" + p.getText();
    }
}

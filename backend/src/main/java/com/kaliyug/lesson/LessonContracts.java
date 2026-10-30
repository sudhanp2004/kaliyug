package com.kaliyug.lesson;

import java.util.List;

/** JSON shapes the AI returns for lessons, and the shapes a lesson is stored in. */
public final class LessonContracts {

    private LessonContracts() {
    }

    // AI replies

    public record LessonReply(Part idea, Part howItWorks, List<Remember> remember, List<String> diagrams,
            String leftOut, String youtubeQuery, MiniDesignSpec miniDesign) {
    }

    public record LinksReply(List<LinkChoice> links) {
    }

    // Shared parts

    public record Part(String text, int firstPage, int lastPage) {
    }

    public record Remember(String text, int firstPage, int lastPage, String evidence) {
    }

    public record MiniDesignSpec(String task, List<String> requirements, List<Long> pointIds) {
    }

    public record LinkChoice(Long mapItemId, String why) {
    }

    // Stored content

    public record LessonContent(List<Block> blocks, List<String> diagrams, String leftOut, String youtubeQuery,
            MiniDesignSpec miniDesign) {
    }

    /** One budget-sized part of a section. */
    public record Block(Part idea, Part howItWorks, List<Remember> remember) {
    }

    public record Links(List<LinkChoice> links) {
    }
}

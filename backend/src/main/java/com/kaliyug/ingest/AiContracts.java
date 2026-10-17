package com.kaliyug.ingest;

import java.util.List;

import com.kaliyug.domain.ChapterKind;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.QuestionType;

/** JSON shapes the AI is asked to return during ingestion. */
public final class AiContracts {

    private AiContracts() {
    }

    public record ChapterKinds(List<ChapterKindItem> chapters) {
    }

    public record ChapterKindItem(int index, ChapterKind kind) {
    }

    public record Sections(List<Section> sections) {
    }

    public record Section(String title, int startPage, int endPage) {
    }

    public record ExtractedPoints(List<ExtractedPoint> points) {
    }

    public record ExtractedPoint(PointKind kind, String text, int firstPage, int lastPage, String evidence,
            List<ExtractedQuestion> questions) {
    }

    public record ExtractedQuestion(QuestionType type, String prompt, String modelAnswer) {
    }

    public record ProblemSpec(String title, List<String> requirements, List<RefComponent> components,
            List<RefDecision> decisions) {
    }

    public record RefComponent(String name, String role, List<Long> pointIds) {
    }

    public record RefDecision(String decision, List<Long> pointIds) {
    }

    /** What is stored as a problem's reference design. */
    public record Reference(List<RefComponent> components, List<RefDecision> decisions) {
    }
}

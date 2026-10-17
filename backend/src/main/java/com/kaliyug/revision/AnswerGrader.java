package com.kaliyug.revision;

import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kaliyug.domain.PageText;
import com.kaliyug.domain.PageTextRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Verdict;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;

/** Judges an answer against the book. Blank answers and exact quick-fire matches never reach the AI. */
@Component
public class AnswerGrader {

    private final JsonLlm ai;
    private final Prompts prompts;
    private final PageTextRepository pages;

    public AnswerGrader(JsonLlm ai, Prompts prompts, PageTextRepository pages) {
        this.ai = ai;
        this.prompts = prompts;
        this.pages = pages;
    }

    public AnswerGrade grade(Question q, String answer) {
        if (answer == null || answer.isBlank()) {
            return new AnswerGrade(Verdict.WRONG, "No answer given.", false);
        }
        if (q.getType() == QuestionType.QUICKFIRE && quickNormalize(answer).equals(quickNormalize(q.getModelAnswer()))) {
            return new AnswerGrade(Verdict.CORRECT, "Exactly right.", false);
        }
        Point p = q.getPoint();
        String user = "Question: " + q.getPrompt()
                + "\n\nBook's answer: " + q.getModelAnswer()
                + "\n\nSupporting quote from the book: " + p.getEvidence()
                + "\n\nBook text (pages " + p.getFirstPage() + "-" + p.getLastPage() + "):\n" + bookText(p)
                + "\n\nStudent's answer: " + answer.trim();
        AnswerGrade grade = ai.ask(prompts.get("grade-answer"), user, AnswerGrade.class);
        if (grade.verdict() == null) {
            throw new LlmUnavailableException("AI gave no verdict");
        }
        String feedback = grade.feedback() == null || grade.feedback().isBlank() ? defaultFeedback(grade.verdict()) : grade.feedback();
        return new AnswerGrade(grade.verdict(), feedback, grade.notInBook());
    }

    String bookText(Point p) {
        Long resourceId = p.getMapItem().getChapter().getResource().getId();
        return pages.findByResourceIdAndPageNumberBetweenOrderByPageNumber(resourceId, p.getFirstPage(), p.getLastPage())
                .stream().map(PageText::getText).collect(Collectors.joining("\n"));
    }

    static String quickNormalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\b(approximately|about)\\b", "")
                .replaceAll("[\\s,]", "");
    }

    private static String defaultFeedback(Verdict v) {
        return switch (v) {
            case CORRECT -> "Right.";
            case PARTIAL -> "Partly right.";
            case WRONG -> "Not quite.";
        };
    }
}

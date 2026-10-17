package com.kaliyug.revision;

import com.kaliyug.domain.Verdict;

/** The AI's judgement of one answer (also the JSON shape the AI returns). */
public record AnswerGrade(Verdict verdict, String feedback, boolean notInBook) {
}

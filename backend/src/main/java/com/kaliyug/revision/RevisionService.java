package com.kaliyug.revision;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Attempt;
import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Chapter;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointKind;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Question;
import com.kaliyug.domain.QuestionRepository;
import com.kaliyug.domain.QuestionType;
import com.kaliyug.domain.Verdict;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.LlmUnavailableException;
import com.kaliyug.llm.Prompts;

/** The revision loop: what to ask next, grading, overrides and flags. */
@Service
public class RevisionService {

    static final int PROBLEM_EVERY = 10;

    public record RevisionItemDto(String type, QuestionView question, ProblemView problem) {
    }

    public record QuestionView(Long id, QuestionType type, String prompt, Long pointId, String bookTitle, String chapterTitle) {
    }

    public record ProblemView(Long id, String title, List<String> requirements, String chapterTitle) {
    }

    public record GradeResultDto(Long attemptId, Verdict verdict, String feedback, boolean notInBook, String modelAnswer,
            int firstPage, int lastPage, String bookTitle) {
    }

    private final PointRepository points;
    private final QuestionRepository questions;
    private final ProblemRepository problems;
    private final AttemptRepository attempts;
    private final AnswerGrader grader;
    private final Scheduler scheduler;
    private final JsonLlm ai;
    private final Prompts prompts;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RevisionService(PointRepository points, QuestionRepository questions, ProblemRepository problems,
            AttemptRepository attempts, AnswerGrader grader, Scheduler scheduler, JsonLlm ai, Prompts prompts,
            ObjectMapper mapper, Clock clock) {
        this.points = points;
        this.questions = questions;
        this.problems = problems;
        this.attempts = attempts;
        this.grader = grader;
        this.scheduler = scheduler;
        this.ai = ai;
        this.prompts = prompts;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public RevisionItemDto next(int answeredInSession, Set<Long> excludePointIds) {
        Instant now = clock.instant();
        List<Point> due = points.findDue(now).stream().filter(p -> !excludePointIds.contains(p.getId())).toList();
        List<Problem> dueProblems = problems.findDue(now);
        boolean problemTurn = answeredInSession >= PROBLEM_EVERY && answeredInSession % PROBLEM_EVERY == 0;
        if (!dueProblems.isEmpty() && (problemTurn || due.isEmpty())) {
            return problemItem(dueProblems.get(0));
        }
        for (Point p : due) {
            Question q = activeQuestion(p);
            if (q != null) {
                q.setLastAskedAt(now);
                Chapter c = p.getMapItem().getChapter();
                return new RevisionItemDto("QUESTION",
                        new QuestionView(q.getId(), q.getType(), q.getPrompt(), p.getId(), c.getResource().getTitle(), c.getTitle()),
                        null);
            }
        }
        return new RevisionItemDto("DONE", null, null);
    }

    private RevisionItemDto problemItem(Problem problem) {
        List<String> requirements = mapper.readValue(problem.getRequirementsJson(), new TypeReference<List<String>>() {
        });
        return new RevisionItemDto("PROBLEM", null,
                new ProblemView(problem.getId(), problem.getTitle(), requirements, problem.getChapter().getTitle()));
    }

    /** The least recently asked active question, or a freshly written one when all were flagged. Null if the AI is down. */
    private Question activeQuestion(Point p) {
        List<Question> active = questions.findByPointIdAndRetiredFalseOrderByLastAskedAtAscIdAsc(p.getId());
        if (!active.isEmpty()) {
            return active.get(0);
        }
        try {
            String user = "Point: " + p.getText() + "\n\nSupporting quote from the book: " + p.getEvidence()
                    + "\n\nBook text (pages " + p.getFirstPage() + "-" + p.getLastPage() + "):\n" + grader.bookText(p);
            NewQuestion fresh = ai.ask(prompts.get("new-question"), user, NewQuestion.class);
            if (fresh.prompt() == null || fresh.prompt().isBlank() || fresh.modelAnswer() == null || fresh.modelAnswer().isBlank()) {
                return null;
            }
            QuestionType type = p.getKind() == PointKind.NUMBER ? QuestionType.QUICKFIRE : QuestionType.SHORT;
            return questions.save(new Question(p, type, fresh.prompt().trim(), fresh.modelAnswer().trim()));
        } catch (LlmUnavailableException e) {
            return null;
        }
    }

    @Transactional
    public GradeResultDto answer(Long questionId, String answer) {
        Question q = questions.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question"));
        AnswerGrade grade = grader.grade(q, answer);
        Point p = q.getPoint();
        Attempt attempt = attempts.save(new Attempt(p, q, null, grade.verdict(), answer, clock.instant()));
        scheduler.apply(p, grade.verdict());
        return new GradeResultDto(attempt.getId(), grade.verdict(), grade.feedback(), grade.notInBook(), q.getModelAnswer(),
                p.getFirstPage(), p.getLastPage(), p.getMapItem().getChapter().getResource().getTitle());
    }

    @Transactional
    public void override(Long attemptId) {
        Attempt a = attempts.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such attempt"));
        if (a.getVerdict() == Verdict.CORRECT || a.isOverridden()) {
            return;
        }
        Point p = a.getPoint();
        p.setBox(a.getBoxBefore());
        p.setDueAt(a.getDueBefore());
        p.setKnownWell(a.isKnownWellBefore());
        scheduler.apply(p, Verdict.CORRECT);
        a.setVerdict(Verdict.CORRECT);
        a.setOverridden(true);
    }

    @Transactional
    public void flag(Long questionId) {
        questions.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question"))
                .setRetired(true);
    }
}

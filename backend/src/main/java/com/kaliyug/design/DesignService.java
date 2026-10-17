package com.kaliyug.design;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.kaliyug.domain.Attempt;
import com.kaliyug.domain.AttemptRepository;
import com.kaliyug.domain.Point;
import com.kaliyug.domain.PointRepository;
import com.kaliyug.domain.Problem;
import com.kaliyug.domain.ProblemRepository;
import com.kaliyug.domain.Verdict;
import com.kaliyug.llm.JsonLlm;
import com.kaliyug.llm.Prompts;
import com.kaliyug.revision.Scheduler;

/** Hands-on system design: the user's canvas is compared with the book's design. */
@Service
public class DesignService {

    public record ProblemDto(Long id, String title, List<String> requirements, String chapterTitle, String bookTitle) {
    }

    public record DesignSubmission(List<Node> nodes, List<Edge> edges, String notes) {
    }

    public record Node(String id, String label) {
    }

    public record Edge(String from, String to, String label) {
    }

    /** JSON shape the AI returns. */
    public record DesignGrade(List<Long> coveredPointIds, List<Long> missedPointIds, String feedback, String differences) {
    }

    public record DesignResultDto(String feedback, String differences, List<PointResultDto> points) {
    }

    public record PointResultDto(Long pointId, String text, int firstPage, int lastPage, Verdict verdict, Long attemptId) {
    }

    private final ProblemRepository problems;
    private final PointRepository points;
    private final AttemptRepository attempts;
    private final Scheduler scheduler;
    private final JsonLlm ai;
    private final Prompts prompts;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DesignService(ProblemRepository problems, PointRepository points, AttemptRepository attempts, Scheduler scheduler, JsonLlm ai,
            Prompts prompts, ObjectMapper mapper, Clock clock) {
        this.problems = problems;
        this.points = points;
        this.attempts = attempts;
        this.scheduler = scheduler;
        this.ai = ai;
        this.prompts = prompts;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProblemDto problem(Long id) {
        Problem p = find(id);
        return new ProblemDto(p.getId(), p.getTitle(), requirements(p), p.getChapter().getTitle(),
                p.getChapter().getResource().getTitle());
    }

    @Transactional
    public DesignResultDto submit(Long id, DesignSubmission submission) {
        if (submission.nodes() == null || submission.nodes().isEmpty()) {
            throw new IllegalArgumentException("Add at least one box before submitting.");
        }
        Problem problem = find(id);
        Map<Long, Point> byId = problem.getPoints().stream().collect(Collectors.toMap(Point::getId, Function.identity()));
        String design = describe(submission);

        StringBuilder user = new StringBuilder("Problem: ").append(problem.getTitle()).append("\n\nRequirements:\n");
        requirements(problem).forEach(r -> user.append("- ").append(r).append('\n'));
        user.append("\nThe book's design (components and key decisions):\n").append(problem.getReferenceJson());
        user.append("\n\nPoints (id | text | pages):\n");
        sorted(byId.values()).forEach(p -> user.append(p.getId()).append(" | ").append(p.getText())
                .append(" | pp. ").append(p.getFirstPage()).append('-').append(p.getLastPage()).append('\n'));
        user.append("\nStudent's design:\n").append(design);

        DesignGrade grade = ai.ask(prompts.get("grade-design"), user.toString(), DesignGrade.class);

        Set<Long> covered = keep(grade.coveredPointIds(), byId.keySet());
        Set<Long> missed = keep(grade.missedPointIds(), byId.keySet());
        missed.removeAll(covered);

        List<PointResultDto> results = new ArrayList<>();
        for (Point p : sorted(byId.values())) {
            Verdict verdict = covered.contains(p.getId()) ? Verdict.CORRECT : missed.contains(p.getId()) ? Verdict.WRONG : null;
            if (verdict == null) {
                continue;
            }
            Attempt attempt = attempts.save(new Attempt(p, null, problem, verdict, design, clock.instant()));
            scheduler.apply(p, verdict);
            results.add(new PointResultDto(p.getId(), p.getText(), p.getFirstPage(), p.getLastPage(), verdict, attempt.getId()));
        }
        scheduler.applyProblem(problem, missed.isEmpty());
        return new DesignResultDto(grade.feedback() == null ? "" : grade.feedback(),
                grade.differences() == null ? "" : grade.differences(), results);
    }

    /** The canvas as plain text, since the AI only reads text. */
    static String describe(DesignSubmission s) {
        Map<String, String> labels = s.nodes().stream()
                .collect(Collectors.toMap(Node::id, Node::label, (a, b) -> a));
        StringBuilder out = new StringBuilder("Boxes:\n");
        s.nodes().forEach(n -> out.append("- ").append(n.label()).append('\n'));
        out.append("Connections:\n");
        if (s.edges() != null) {
            for (Edge e : s.edges()) {
                out.append("- ").append(labels.getOrDefault(e.from(), e.from())).append(" -> ")
                        .append(labels.getOrDefault(e.to(), e.to()));
                if (e.label() != null && !e.label().isBlank()) {
                    out.append(" (").append(e.label().trim()).append(')');
                }
                out.append('\n');
            }
        }
        out.append("Notes:\n").append(s.notes() == null ? "" : s.notes().trim());
        return out.toString();
    }

    private List<String> requirements(Problem p) {
        return Requirements.read(mapper, p.getRequirementsJson(), points.findIdsByChapterId(p.getChapter().getId()));
    }

    private static Set<Long> keep(List<Long> ids, Set<Long> allowed) {
        Set<Long> kept = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(allowed::contains).forEach(kept::add);
        }
        return kept;
    }

    private static List<Point> sorted(Collection<Point> points) {
        return points.stream().sorted(Comparator.comparingInt(Point::getFirstPage).thenComparing(Point::getId)).toList();
    }

    private Problem find(Long id) {
        return problems.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such problem"));
    }
}

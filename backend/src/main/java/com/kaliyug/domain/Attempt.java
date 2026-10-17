package com.kaliyug.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;

/** One graded answer about one point; keeps the point's prior schedule so an override can restore it. */
@Entity
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Point point;
    @ManyToOne(fetch = FetchType.LAZY)
    private Question question;
    @ManyToOne(fetch = FetchType.LAZY)
    private Problem problem;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(32)")
    private Verdict verdict;
    private boolean overridden;
    private int boxBefore;
    private Instant dueBefore;
    private boolean knownWellBefore;
    @Lob
    private String answer;
    private Instant at;

    protected Attempt() {
    }

    /** Captures the point's current schedule as the "before" state. */
    public Attempt(Point point, Question question, Problem problem, Verdict verdict, String answer, Instant at) {
        this.point = point;
        this.question = question;
        this.problem = problem;
        this.verdict = verdict;
        this.answer = answer;
        this.at = at;
        this.boxBefore = point.getBox();
        this.dueBefore = point.getDueAt();
        this.knownWellBefore = point.isKnownWell();
    }

    public Long getId() { return id; }
    public Point getPoint() { return point; }
    public Question getQuestion() { return question; }
    public Problem getProblem() { return problem; }
    public Verdict getVerdict() { return verdict; }
    public void setVerdict(Verdict verdict) { this.verdict = verdict; }
    public boolean isOverridden() { return overridden; }
    public void setOverridden(boolean overridden) { this.overridden = overridden; }
    public int getBoxBefore() { return boxBefore; }
    public Instant getDueBefore() { return dueBefore; }
    public boolean isKnownWellBefore() { return knownWellBefore; }
    public String getAnswer() { return answer; }
    public Instant getAt() { return at; }
}

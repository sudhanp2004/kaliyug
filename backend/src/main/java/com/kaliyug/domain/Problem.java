package com.kaliyug.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;

/** A hands-on system design exercise built from a design chapter. */
@Entity
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private Chapter chapter;
    private String title;
    @Lob
    private String requirementsJson;
    @Lob
    private String referenceJson;
    @ManyToMany
    private Set<Point> points = new HashSet<>();
    private int box;
    /** Null until every item of the chapter is learned. */
    private Instant dueAt;

    protected Problem() {
    }

    public Problem(Chapter chapter, String title, String requirementsJson, String referenceJson) {
        this.chapter = chapter;
        this.title = title;
        this.requirementsJson = requirementsJson;
        this.referenceJson = referenceJson;
    }

    public Long getId() { return id; }
    public Chapter getChapter() { return chapter; }
    public String getTitle() { return title; }
    public String getRequirementsJson() { return requirementsJson; }
    public String getReferenceJson() { return referenceJson; }
    public Set<Point> getPoints() { return points; }
    public void setPoints(Set<Point> points) { this.points = new HashSet<>(points); }
    public int getBox() { return box; }
    public void setBox(int box) { this.box = box; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
}

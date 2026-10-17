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

@Entity
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Point point;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(32)")
    private QuestionType type;
    @Lob
    private String prompt;
    @Lob
    private String modelAnswer;
    private boolean retired;
    private Instant lastAskedAt;

    protected Question() {
    }

    public Question(Point point, QuestionType type, String prompt, String modelAnswer) {
        this.point = point;
        this.type = type;
        this.prompt = prompt;
        this.modelAnswer = modelAnswer;
    }

    public Long getId() { return id; }
    public Point getPoint() { return point; }
    public QuestionType getType() { return type; }
    public String getPrompt() { return prompt; }
    public String getModelAnswer() { return modelAnswer; }
    public boolean isRetired() { return retired; }
    public void setRetired(boolean retired) { this.retired = retired; }
    public Instant getLastAskedAt() { return lastAskedAt; }
    public void setLastAskedAt(Instant lastAskedAt) { this.lastAskedAt = lastAskedAt; }
}

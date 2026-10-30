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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;

/** The generated lesson that teaches one map item. */
@Entity
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(unique = true)
    private MapItem mapItem;
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(32)")
    private LessonStatus status = LessonStatus.PREPARING;
    /** LessonContracts.LessonContent as JSON; null until READY. */
    @Lob
    private String contentJson;
    private int readingMinutes;
    /** Points the coverage check added to a section that was already learned. */
    private int newPointsFound;
    /** LessonContracts.Links as JSON: the "Builds on" sections. */
    @Lob
    private String linksJson;
    /** How many sections were learned when the links were last chosen; null means never chosen. */
    private Long linksAtLearnedCount;
    @Column(length = Text.REASON)
    private String failureReason;
    private Instant updatedAt;

    protected Lesson() {
    }

    public Lesson(MapItem mapItem) {
        this.mapItem = mapItem;
    }

    public Long getId() { return id; }
    public MapItem getMapItem() { return mapItem; }
    public LessonStatus getStatus() { return status; }
    public void setStatus(LessonStatus status) { this.status = status; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public int getReadingMinutes() { return readingMinutes; }
    public void setReadingMinutes(int readingMinutes) { this.readingMinutes = readingMinutes; }
    public int getNewPointsFound() { return newPointsFound; }
    public void setNewPointsFound(int newPointsFound) { this.newPointsFound = newPointsFound; }
    public String getLinksJson() { return linksJson; }
    public void setLinksJson(String linksJson) { this.linksJson = linksJson; }
    public Long getLinksAtLearnedCount() { return linksAtLearnedCount; }
    public void setLinksAtLearnedCount(Long linksAtLearnedCount) { this.linksAtLearnedCount = linksAtLearnedCount; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = Text.clip(failureReason, Text.REASON); }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

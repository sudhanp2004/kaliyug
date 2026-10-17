package com.kaliyug.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** One chunk of a chapter the user goes and learns from the book. */
@Entity
public class MapItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Chapter chapter;
    private int orderIndex;
    private String title;
    private int startPage;
    private int endPage;
    private boolean ingested;
    private boolean hidden;
    /** Null while the item is still queued. */
    private Instant learnedAt;

    protected MapItem() {
    }

    public MapItem(Chapter chapter, int orderIndex, String title, int startPage, int endPage) {
        this.chapter = chapter;
        this.orderIndex = orderIndex;
        this.title = title;
        this.startPage = startPage;
        this.endPage = endPage;
    }

    public Long getId() { return id; }
    public Chapter getChapter() { return chapter; }
    public int getOrderIndex() { return orderIndex; }
    public String getTitle() { return title; }
    public int getStartPage() { return startPage; }
    public int getEndPage() { return endPage; }
    public boolean isIngested() { return ingested; }
    public void setIngested(boolean ingested) { this.ingested = ingested; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    public Instant getLearnedAt() { return learnedAt; }
    public void setLearnedAt(Instant learnedAt) { this.learnedAt = learnedAt; }
    public boolean isLearned() { return learnedAt != null; }
}

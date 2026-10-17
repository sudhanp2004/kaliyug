package com.kaliyug.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A top-level bookmark of a book. */
@Entity
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Resource resource;
    private int orderIndex;
    private String title;
    private int startPage;
    private int endPage;
    @Enumerated(EnumType.STRING)
    private ChapterKind kind;
    private boolean sectionsSplit;

    protected Chapter() {
    }

    public Chapter(Resource resource, int orderIndex, String title, int startPage, int endPage, ChapterKind kind) {
        this.resource = resource;
        this.orderIndex = orderIndex;
        this.title = title;
        this.startPage = startPage;
        this.endPage = endPage;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public Resource getResource() { return resource; }
    public int getOrderIndex() { return orderIndex; }
    public String getTitle() { return title; }
    public int getStartPage() { return startPage; }
    public int getEndPage() { return endPage; }
    public ChapterKind getKind() { return kind; }
    public void setKind(ChapterKind kind) { this.kind = kind; }
    public boolean isSectionsSplit() { return sectionsSplit; }
    public void setSectionsSplit(boolean sectionsSplit) { this.sectionsSplit = sectionsSplit; }
}

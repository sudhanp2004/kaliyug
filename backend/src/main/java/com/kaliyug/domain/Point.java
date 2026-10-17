package com.kaliyug.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;

/** One atomic thing worth knowing, grounded in the book; the unit progress counts and revision schedules. */
@Entity
public class Point {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private MapItem mapItem;
    @Enumerated(EnumType.STRING)
    private PointKind kind;
    @Lob
    private String text;
    private int firstPage;
    private int lastPage;
    @Lob
    private String evidence;
    private int box;
    /** Null until the point's map item is marked learned. */
    private Instant dueAt;
    private boolean knownWell;

    protected Point() {
    }

    public Point(MapItem mapItem, PointKind kind, String text, int firstPage, int lastPage, String evidence) {
        this.mapItem = mapItem;
        this.kind = kind;
        this.text = text;
        this.firstPage = firstPage;
        this.lastPage = lastPage;
        this.evidence = evidence;
    }

    public Long getId() { return id; }
    public MapItem getMapItem() { return mapItem; }
    public PointKind getKind() { return kind; }
    public String getText() { return text; }
    public int getFirstPage() { return firstPage; }
    public int getLastPage() { return lastPage; }
    public String getEvidence() { return evidence; }
    public int getBox() { return box; }
    public void setBox(int box) { this.box = box; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public boolean isKnownWell() { return knownWell; }
    public void setKnownWell(boolean knownWell) { this.knownWell = knownWell; }
}

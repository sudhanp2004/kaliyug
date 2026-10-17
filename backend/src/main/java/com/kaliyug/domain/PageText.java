package com.kaliyug.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;

/** Extracted text of one PDF page (1-based page number). */
@Entity
public class PageText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Resource resource;
    private int pageNumber;
    @Lob
    private String text;

    protected PageText() {
    }

    public PageText(Resource resource, int pageNumber, String text) {
        this.resource = resource;
        this.pageNumber = pageNumber;
        this.text = text;
    }

    public Long getId() { return id; }
    public Resource getResource() { return resource; }
    public int getPageNumber() { return pageNumber; }
    public String getText() { return text; }
}

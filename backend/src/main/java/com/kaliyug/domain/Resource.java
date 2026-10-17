package com.kaliyug.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** An uploaded book. */
@Entity
public class Resource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String storedPath;
    @Column(unique = true)
    private String sha256;
    @Enumerated(EnumType.STRING)
    private ResourceStatus status = ResourceStatus.INGESTING;
    @Column(length = 1000)
    private String failureReason;
    private int totalPages;
    private Instant createdAt;

    protected Resource() {
    }

    public Resource(String title, String storedPath, String sha256, Instant createdAt) {
        this.title = title;
        this.storedPath = storedPath;
        this.sha256 = sha256;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStoredPath() { return storedPath; }
    public String getSha256() { return sha256; }
    public ResourceStatus getStatus() { return status; }
    public void setStatus(ResourceStatus status) { this.status = status; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public Instant getCreatedAt() { return createdAt; }
}

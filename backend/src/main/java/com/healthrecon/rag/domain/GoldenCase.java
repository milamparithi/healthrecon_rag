package com.healthrecon.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "golden_case")
public class GoldenCase {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_GOLDEN = "GOLDEN";

    @Id
    private UUID id;

    @Column(name = "doc_set_id", nullable = false)
    private UUID docSetId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "source_doc_id")
    private UUID sourceDocId;

    @Column(nullable = false)
    private String question;

    @Column(name = "reference_answer", nullable = false)
    private String referenceAnswer;

    @Column(name = "expected_sources", nullable = false)
    private String expectedSources;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GoldenCase() {
    }

    public GoldenCase(UUID id, UUID docSetId, UUID ownerId, UUID sourceDocId, String question,
                      String referenceAnswer, String expectedSources, String status,
                      Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.docSetId = docSetId;
        this.ownerId = ownerId;
        this.sourceDocId = sourceDocId;
        this.question = question;
        this.referenceAnswer = referenceAnswer;
        this.expectedSources = expectedSources;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocSetId() {
        return docSetId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getSourceDocId() {
        return sourceDocId;
    }

    public String getQuestion() {
        return question;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public String getExpectedSources() {
        return expectedSources;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String question, String referenceAnswer, String expectedSources, String status,
                       Instant now) {
        this.question = question;
        this.referenceAnswer = referenceAnswer;
        this.expectedSources = expectedSources;
        this.status = status;
        this.updatedAt = now;
    }
}
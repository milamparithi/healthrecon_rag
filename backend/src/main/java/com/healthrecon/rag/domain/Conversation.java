package com.healthrecon.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation")
public class Conversation {

    public static final String DEFAULT_TITLE = "New conversation";

    @Id
    private UUID id;

    @Column(name = "doc_set_id", nullable = false)
    private UUID docSetId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(UUID id, UUID docSetId, UUID ownerId, String title, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.docSetId = docSetId;
        this.ownerId = ownerId;
        this.title = title;
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

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public void rename(String title, Instant now) {
        this.title = title;
        this.updatedAt = now;
    }
}
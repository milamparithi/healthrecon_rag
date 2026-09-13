package com.healthrecon.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_message")
public class ChatMessage {

    public enum Role {
        USER,
        ASSISTANT
    }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private String content;

    /** JSON-encoded list of sources, only populated on assistant messages. */
    @Column
    private String sources;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessage() {
    }

    public ChatMessage(UUID id, UUID conversationId, Role role, String content, String sources, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.sources = sources;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getSources() {
        return sources;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
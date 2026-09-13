package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.Conversation;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(UUID id, String title, Instant createdAt, Instant updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(), conversation.getTitle(),
                conversation.getCreatedAt(), conversation.getUpdatedAt());
    }
}
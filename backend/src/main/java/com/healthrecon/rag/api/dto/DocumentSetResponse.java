package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentSetResponse(
        UUID id,
        String name,
        String description,
        DocumentSetStatus status,
        long documentCount,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentSetResponse from(DocumentSet set, long documentCount) {
        return new DocumentSetResponse(
                set.getId(), set.getName(), set.getDescription(),
                set.getStatus(), documentCount,
                set.getCreatedAt(), set.getUpdatedAt());
    }
}
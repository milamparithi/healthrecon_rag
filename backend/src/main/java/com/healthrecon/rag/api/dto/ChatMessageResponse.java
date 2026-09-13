package com.healthrecon.rag.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatMessageResponse(
        UUID id,
        String role,
        String content,
        List<SourceResponse> sources,
        Instant createdAt) {
}
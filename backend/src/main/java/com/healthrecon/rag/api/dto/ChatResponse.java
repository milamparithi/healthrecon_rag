package com.healthrecon.rag.api.dto;

import java.util.List;
import java.util.UUID;

public record ChatResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<SourceResponse> sources,
        String title) {
}
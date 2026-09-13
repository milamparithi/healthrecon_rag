package com.healthrecon.rag.api.dto;

import jakarta.validation.constraints.Size;

public record ConversationRequest(
        @Size(max = 200, message = "title must be at most 200 characters")
        String title) {
}
package com.healthrecon.rag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "message is required")
        @Size(max = 4096, message = "message must be at most 4096 characters")
        String message) {
}
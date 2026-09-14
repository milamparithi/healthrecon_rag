package com.healthrecon.rag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record GoldenCaseRequest(
        @NotBlank(message = "question is required")
        @Size(max = 2000, message = "question must be at most 2000 characters")
        String question,
        @NotBlank(message = "answer is required")
        @Size(max = 20000, message = "answer must be at most 20000 characters")
        String answer,
        List<ExpectedSource> expectedSources,
        @Size(max = 10, message = "status is invalid")
        String status) {
}
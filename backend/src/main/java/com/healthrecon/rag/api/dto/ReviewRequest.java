package com.healthrecon.rag.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @NotBlank(message = "verdict is required")
        @Size(max = 10, message = "verdict is invalid")
        String verdict,
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        Integer rating,
        @Size(max = 4000, message = "comment must be at most 4000 characters")
        String comment,
        @Size(max = 20000, message = "correctedAnswer must be at most 20000 characters")
        String correctedAnswer) {
}
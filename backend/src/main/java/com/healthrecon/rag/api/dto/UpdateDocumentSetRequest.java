package com.healthrecon.rag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDocumentSetRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description) {
}
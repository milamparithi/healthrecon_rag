package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentDetail(
        UUID id,
        String filename,
        String contentType,
        long contentLength,
        DocumentStatus status,
        String error,
        String extractedText,
        int extractedTextLength,
        Instant createdAt) {

    public DocumentDetail {
        extractedText = extractedText == null ? "" : extractedText;
        extractedTextLength = extractedText.length();
    }
}
package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.repository.DocumentRepository;

import java.time.Instant;
import java.util.UUID;

public record DocumentListItem(
        UUID id,
        String filename,
        String contentType,
        long contentLength,
        DocumentStatus status,
        String error,
        Instant createdAt) {

    public static DocumentListItem from(DocumentRepository.DocumentListItem item) {
        return new DocumentListItem(
                item.getId(), item.getFilename(), item.getContentType(),
                item.getContentLength(), item.getStatus(), item.getError(), item.getCreatedAt());
    }
}
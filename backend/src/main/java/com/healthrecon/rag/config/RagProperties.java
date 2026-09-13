package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        int topK,
        int maxHistoryMessages,
        String systemPrompt,
        Chunking chunking) {

    public record Chunking(String mode, int chunkSize, int chunkOverlap, int maxChunksPerDoc) {
    }
}
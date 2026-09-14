package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        int topK,
        int maxHistoryMessages,
        String systemPrompt,
        GuardrailProperties guardrails,
        CacheProperties cache,
        SearchProperties search,
        QueryRewrite queryRewrite,
        Chunking chunking) {

    public record QueryRewrite(boolean enabled, int maxChars, String systemPrompt) {
    }

    public record Chunking(String mode, int chunkSize, int chunkOverlap, int maxChunksPerDoc) {
    }
}
package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String chatModel,
        String embeddingModel,
        int embeddingDimension) {
}
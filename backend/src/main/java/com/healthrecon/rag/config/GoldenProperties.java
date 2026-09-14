package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.golden")
public record GoldenProperties(
        boolean enabled,
        long pollMs,
        long initialDelayMs,
        int questionsPerDoc,
        int maxCharsPerDoc,
        int maxRetries) {
}
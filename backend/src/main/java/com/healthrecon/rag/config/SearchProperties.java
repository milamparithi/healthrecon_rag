package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval-stage tuning: hybrid (dense + sparse BM25) search plus an
 * optional post-fusion reranker.
 */
@ConfigurationProperties(prefix = "app.rag.search")
public record SearchProperties(
        boolean hybridEnabled,
        int candidates,
        String fusion,
        Rerank rerank) {

    public record Rerank(boolean enabled, String mode) {
    }
}
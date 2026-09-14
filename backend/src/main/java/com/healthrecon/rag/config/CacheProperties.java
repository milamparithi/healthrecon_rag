package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Semantic response cache for the RAG chat. Cached answers are stored in a
 * dedicated Qdrant collection, keyed by the embedding of the user question and
 * scoped to the owning document set. Entries expire after {@code ttlSeconds}.
 */
@ConfigurationProperties(prefix = "app.rag.cache")
public record CacheProperties(
        boolean enabled,
        double similarityThreshold,
        long ttlSeconds,
        String collectionName) {
}
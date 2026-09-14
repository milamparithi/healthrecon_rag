package com.healthrecon.rag.service.semanticcache;

import com.healthrecon.rag.api.dto.SourceResponse;
import dev.langchain4j.data.embedding.Embedding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic response cache for the chat: stores the final grounded answer for a
 * question keyed by its embedding, scoped to the owning document set. Lookups
 * match on similarity (cosine) above the configured threshold. Only validated,
 * grounded answers are ever stored; the store is invalidated wholesale whenever
 * the set's indexed documents change.
 */
public interface SemanticCache {

    Optional<CachedAnswer> lookup(UUID docSetId, Embedding queryEmbedding);

    void store(UUID docSetId, Embedding queryEmbedding, String question, String answer,
               List<SourceResponse> sources);

    void invalidate(UUID docSetId);
}
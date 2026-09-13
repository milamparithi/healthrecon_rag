package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.service.chunking.TextChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;
import java.util.UUID;

/**
 * Stores and queries vectorized chunks. Implementations must be idempotent
 * (re-indexing the same document replaces its previous vectors) and tolerate
 * missing collections (deleted sets).
 */
public interface VectorIndexer {

    /** Ensures a collection/partition exists for the given document set. */
    void ensureCollection(UUID docSetId);

    /**
     * Upserts chunks for a document. Order of {@code chunks}, {@code segments}
     * and {@code embeddings} matches.
     */
    void upsert(StoredDocument doc, List<TextChunk> chunks, List<TextSegment> segments, List<Embedding> embeddings);

    /** Removes all vectors belonging to a single document. */
    void deleteDocument(UUID docSetId, UUID docId);

    /** Drops everything belonging to a document set. */
    void deleteSet(UUID docSetId);

    List<ChunkSearchHit> search(UUID docSetId, Embedding queryEmbedding, int topK);
}
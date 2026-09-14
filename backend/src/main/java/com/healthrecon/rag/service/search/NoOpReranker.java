package com.healthrecon.rag.service.search;

import com.healthrecon.rag.service.ChunkSearchHit;

import java.util.List;
import java.util.UUID;

/**
 * Pass-through reranker used when reranking is disabled
 * ({@code RAG_RERANK_ENABLED=false}). Returns the fused candidates sliced to
 * {@code topK}, preserving their retrieval order.
 */
public class NoOpReranker implements Reranker {

    @Override
    public List<ChunkSearchHit> rerank(UUID docSetId, String query, List<ChunkSearchHit> candidates, int topK) {
        int end = Math.min(topK, candidates.size());
        return List.copyOf(candidates.subList(0, end));
    }
}
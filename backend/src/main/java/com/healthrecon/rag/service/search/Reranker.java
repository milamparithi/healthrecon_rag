package com.healthrecon.rag.service.search;

import com.healthrecon.rag.service.ChunkSearchHit;

import java.util.List;
import java.util.UUID;

/**
 * Re-orders fused retrieval candidates, returning the {@code topK} most
 * relevant passages for the query. Best-effort: implementations must degrade
 * to the original candidate order (sliced to {@code topK}) on any failure.
 */
public interface Reranker {

    List<ChunkSearchHit> rerank(UUID docSetId, String query, List<ChunkSearchHit> candidates, int topK);
}
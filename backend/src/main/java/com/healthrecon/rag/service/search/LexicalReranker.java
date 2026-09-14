package com.healthrecon.rag.service.search;

import com.healthrecon.rag.service.ChunkSearchHit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Scores each candidate by lexical overlap with the query using the same
 * tokenizer that produced the sparse index, then sorts by that score. Runs
 * fully in-process (no external API) and is deterministic. It never changes
 * the candidate set, only the order, so a rerank failure (or empty query)
 * simply preserves the fused ranking.
 */
public class LexicalReranker implements Reranker {

    private final SparseVectorizer vectorizer;

    public LexicalReranker(SparseVectorizer vectorizer) {
        this.vectorizer = vectorizer;
    }

    @Override
    public List<ChunkSearchHit> rerank(UUID docSetId, String query, List<ChunkSearchHit> candidates, int topK) {
        Set<String> queryTokens = vectorizer.tokenSet(query);
        List<ChunkSearchHit> ordered = new ArrayList<>(candidates);
        ordered.sort((a, b) -> Float.compare(overlap(b, queryTokens), overlap(a, queryTokens)));
        int end = Math.min(topK, ordered.size());
        return List.copyOf(ordered.subList(0, end));
    }

    private float overlap(ChunkSearchHit hit, Set<String> queryTokens) {
        if (queryTokens.isEmpty() || hit.snippet() == null) {
            return 0f;
        }
        Set<String> hitTokens = vectorizer.tokenSet(hit.snippet());
        hitTokens.retainAll(queryTokens);
        return hitTokens.size();
    }
}
package com.healthrecon.rag.service.search;

import com.healthrecon.rag.config.SearchProperties;
import com.healthrecon.rag.service.ChunkSearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Selects the active reranker from configuration and applies it to fused
 * retrieval candidates. Modes:
 * <ul>
 *   <li>{@code none} (default) — pass-through, keeps fused order</li>
 *   <li>{@code lexical} — in-process lexical overlap scoring (no external API)</li>
 * </ul>
 * When reranking is disabled ({@code RAG_RERANK_ENABLED=false}) the candidates
 * are simply sliced to the rerank/final {@code topK}.
 */
@Component
public class RerankerService implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    private final SearchProperties searchProperties;
    private final NoOpReranker noOp;
    private final LexicalReranker lexical;

    public RerankerService(SearchProperties searchProperties, SparseVectorizer vectorizer) {
        this.searchProperties = searchProperties;
        this.noOp = new NoOpReranker();
        this.lexical = new LexicalReranker(vectorizer);
    }

    @Override
    public List<ChunkSearchHit> rerank(UUID docSetId, String query, List<ChunkSearchHit> candidates, int topK) {
        if (!searchProperties.rerank().enabled()) {
            return noOp.rerank(docSetId, query, candidates, topK);
        }
        String mode = searchProperties.rerank().mode();
        try {
            if ("lexical".equals(mode)) {
                return lexical.rerank(docSetId, query, candidates, topK);
            }
            log.warn("Unknown rerank mode '{}'; falling back to no-op", mode);
        } catch (Exception e) {
            log.warn("Reranker failed (mode={}) for set {}: {}", mode, docSetId, e.getMessage());
        }
        return noOp.rerank(docSetId, query, candidates, topK);
    }
}
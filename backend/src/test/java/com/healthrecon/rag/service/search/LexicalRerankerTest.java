package com.healthrecon.rag.service.search;

import com.healthrecon.rag.service.ChunkSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LexicalRerankerTest {

    private final SparseVectorizer vectorizer = new SparseVectorizer();
    private final LexicalReranker reranker = new LexicalReranker(vectorizer);
    private final UUID docSetId = UUID.randomUUID();

    @Test
    void ranksCandidatesByLexicalOverlapWithQuery() {
        ChunkSearchHit cardio = hit("heart rate monitoring protocol");
        ChunkSearchHit injection = hit("intravenous injection administration");

        List<ChunkSearchHit> result = reranker.rerank(docSetId, "heart rate", List.of(injection, cardio), 2);

        assertThat(result.get(0)).isEqualTo(cardio);
        assertThat(result.get(1)).isEqualTo(injection);
    }

    @Test
    void returnsUpToTopKResults() {
        List<ChunkSearchHit> hits = List.of(
                hit("beta blocker medication"),
                hit("diuretic medication"),
                hit("statin medication"));

        List<ChunkSearchHit> result = reranker.rerank(docSetId, "medication", hits, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void emptyQueryKeepsOriginalOrder() {
        ChunkSearchHit a = hit("alpha content here");
        ChunkSearchHit b = hit("beta content here");

        List<ChunkSearchHit> result = reranker.rerank(docSetId, "the and of", List.of(a, b), 2);

        assertThat(result).containsExactly(a, b);
    }

    @Test
    void neverChangesCandidateSet() {
        List<ChunkSearchHit> hits = List.of(
                hit("first document paragraph"),
                hit("second document paragraph"));

        List<ChunkSearchHit> result = reranker.rerank(docSetId, "document", hits, 5);

        assertThat(result).containsExactlyInAnyOrderElementsOf(hits);
    }

    private static ChunkSearchHit hit(String snippet) {
        return new ChunkSearchHit(UUID.randomUUID(), "doc.md", "Section", 0.5, snippet);
    }
}
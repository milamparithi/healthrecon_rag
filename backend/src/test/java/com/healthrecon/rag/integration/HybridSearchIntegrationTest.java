package com.healthrecon.rag.integration;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.service.ChunkSearchHit;
import com.healthrecon.rag.service.QdrantVectorIndexer;
import com.healthrecon.rag.service.chunking.TextChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the hybrid dense+sparse retrieval path against a real Qdrant. All
 * dense embeddings are identical unit vectors, so the cosine leg cannot tell
 * the chunks apart; only the BM25 sparse leg (built client-side from the raw
 * query text) can rank the lexically matching chunk first.
 */
class HybridSearchIntegrationTest extends BaseIntegrationTest {

    private static final int DIM = 768;

    @Autowired
    private QdrantVectorIndexer vectorIndexer;

    @Test
    @DisplayName("sparse BM25 leg ranks the lexically relevant chunk first on dense tie")
    void sparseLegDiscriminatesOnDenseTie() {
        UUID docSetId = UUID.randomUUID();

        StoredDocument cardiology = document(docSetId, "cardiology.md");
        List<TextChunk> cardioChunks = List.of(
                chunk(cardiology.getId(), 0, "Medications", "Beta-blocker medication reduces the heart rate during exercise."),
                chunk(cardiology.getId(), 1, "Monitoring", "Daily blood pressure readings should be recorded in the log."));

        StoredDocument oncology = document(docSetId, "oncology.md");
        List<TextChunk> oncoChunks = List.of(
                chunk(oncology.getId(), 0, "Treatment", "Chemotherapy is administered under strict clinical supervision."),
                chunk(oncology.getId(), 1, "Side effects", "Nausea is a common side effect of the chemotherapy regimen."));

        vectorIndexer.upsert(cardiology, cardioChunks, segments(cardioChunks), allOnes(cardioChunks.size()));
        vectorIndexer.upsert(oncology, oncoChunks, segments(oncoChunks), allOnes(oncoChunks.size()));

        Embedding queryEmbedding = ones(DIM);
        List<ChunkSearchHit> hits = vectorIndexer.search(
                docSetId, "beta blocker medication", queryEmbedding, 2);

        assertThat(hits).isNotEmpty();
        ChunkSearchHit top = hits.get(0);
        assertThat(top.filename()).isEqualTo("cardiology.md");
        assertThat(top.snippet()).contains("Beta-blocker medication");
    }

    @Test
    @DisplayName("hybrid search is scoped to the document set")
    void searchIsScopedToDocumentSet() {
        UUID docSetA = UUID.randomUUID();
        UUID docSetB = UUID.randomUUID();

        StoredDocument docA = document(docSetA, "a.md");
        List<TextChunk> chunksA = List.of(
                chunk(docA.getId(), 0, "Topic", "Atrial fibrillation management guidelines for cardiology staff."));
        StoredDocument docB = document(docSetB, "b.md");
        List<TextChunk> chunksB = List.of(
                chunk(docB.getId(), 0, "Topic", "Atrial fibrillation management guidelines for oncology staff."));

        vectorIndexer.upsert(docA, chunksA, segments(chunksA), allOnes(1));
        vectorIndexer.upsert(docB, chunksB, segments(chunksB), allOnes(1));

        List<ChunkSearchHit> hits = vectorIndexer.search(docSetA, "atrial fibrillation", ones(DIM), 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allMatch(hit -> "a.md".equals(hit.filename()));
    }

    private static StoredDocument document(UUID docSetId, String filename) {
        return StoredDocument.builder()
                .id(UUID.randomUUID())
                .docSetId(docSetId)
                .filename(filename)
                .contentType("text/markdown")
                .contentLength(0)
                .sha256(UUID.randomUUID().toString())
                .content(new byte[0])
                .extractedText(filename)
                .status(DocumentStatus.READY)
                .createdAt(Instant.now())
                .build();
    }

    private static TextChunk chunk(UUID docId, int index, String section, String text) {
        return new TextChunk(docId, index, section, text);
    }

    private static List<TextSegment> segments(List<TextChunk> chunks) {
        return chunks.stream().map(c -> TextSegment.from(c.text())).toList();
    }

    private static List<Embedding> allOnes(int count) {
        final float[] vector = onesArray(DIM);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Embedding(vector))
                .toList();
    }

    private static Embedding ones(int dim) {
        return new Embedding(onesArray(dim));
    }

    private static float[] onesArray(int dim) {
        float[] vector = new float[dim];
        java.util.Arrays.fill(vector, 1f);
        return vector;
    }
}
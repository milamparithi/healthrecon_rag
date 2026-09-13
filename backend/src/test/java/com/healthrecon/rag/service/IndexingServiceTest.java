package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.service.chunking.ChunkingService;
import com.healthrecon.rag.service.chunking.TextChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexingServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorIndexer vectorIndexer;

    @Test
    void indexesChunksWhenDocumentProducesThem() {
        StoredDocument doc = readyDocument();
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkingService.chunk(any(), any(), any(), any())).thenReturn(List.of(
                new TextChunk(doc.getId(), 0, "", "first"),
                new TextChunk(doc.getId(), 1, "Title", "second")));
        when(embeddingModel.embedAll(anyList())).thenReturn(Response.from(List.of(
                new Embedding(new float[]{0.1f}), new Embedding(new float[]{0.2f}))));
        IndexingService service = new IndexingService(documentRepository, chunkingService, embeddingModel, vectorIndexer);

        service.indexDocument(doc);

        assertThat(doc.getIndexStatus()).isEqualTo(IndexStatus.INDEXED);
        assertThat(doc.getIndexError()).isNull();
        verify(vectorIndexer).upsert(any(), anyList(), anyList(), anyList());
    }

    @Test
    void emptyDocumentIsMarkedIndexedWithoutEmbeddingCalls() {
        StoredDocument doc = readyDocument();
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkingService.chunk(any(), any(), any(), any())).thenReturn(List.of());
        IndexingService service = new IndexingService(documentRepository, chunkingService, embeddingModel, vectorIndexer);

        service.indexDocument(doc);

        assertThat(doc.getIndexStatus()).isEqualTo(IndexStatus.INDEXED);
        verify(embeddingModel, never()).embedAll(anyList());
        verifyNoMoreInteractions(vectorIndexer);
    }

    @Test
    void failureMarksDocumentIndexFailed() {
        StoredDocument doc = readyDocument();
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkingService.chunk(any(), any(), any(), any())).thenReturn(List.of(
                new TextChunk(doc.getId(), 0, "", "text")));
        when(embeddingModel.embedAll(anyList())).thenThrow(new RuntimeException("provider down"));
        IndexingService service = new IndexingService(documentRepository, chunkingService, embeddingModel, vectorIndexer);

        service.indexDocument(doc);

        assertThat(doc.getIndexStatus()).isEqualTo(IndexStatus.FAILED);
        assertThat(doc.getIndexError()).contains("provider down");
        verifyNoMoreInteractions(vectorIndexer);
    }

    @Test
    void markIndexedSetsFailureState() {
        StoredDocument doc = readyDocument();
        doc.markIndexFailed("boom");
        doc.markIndexing(Instant.now());
        doc.markIndexed();
        assertThat(doc.getIndexStatus()).isEqualTo(IndexStatus.INDEXED);
        assertThat(doc.getIndexError()).isNull();
    }

    private static StoredDocument readyDocument() {
        StoredDocument doc = StoredDocument.builder()
                .id(UUID.randomUUID())
                .docSetId(UUID.randomUUID())
                .filename("notes.txt")
                .contentType("text/plain")
                .contentLength(4L)
                .sha256("abc")
                .content("data".getBytes())
                .extractedText("data")
                .status(DocumentStatus.READY)
                .createdAt(Instant.now())
                .build();
        doc.markReady("data");
        return doc;
    }
}
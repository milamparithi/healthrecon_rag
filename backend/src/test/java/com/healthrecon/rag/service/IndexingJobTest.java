package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexingJobTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private IndexingService indexingService;

    @Test
    void processesReadyDocumentsThatNeedIndexing() {
        StoredDocument doc = dueDocument();
        when(documentRepository.findAllByStatusAndIndexStatusIn(eq(DocumentStatus.READY), any()))
                .thenReturn(List.of(doc));
        IndexingJob job = new IndexingJob(documentRepository, indexingService);

        job.processDue();

        verify(indexingService).indexDocument(doc);
    }

    @Test
    void queryTargetsNotIndexedAndIndexingDocuments() {
        when(documentRepository.findAllByStatusAndIndexStatusIn(eq(DocumentStatus.READY), any()))
                .thenReturn(List.of());
        IndexingJob job = new IndexingJob(documentRepository, indexingService);

        job.processDue();

        verify(documentRepository).findAllByStatusAndIndexStatusIn(
                eq(DocumentStatus.READY),
                org.mockito.ArgumentMatchers.eq(List.of(IndexStatus.NOT_INDEXED, IndexStatus.INDEXING)));
    }

    @Test
    void continuesPastIndexingFailures() {
        StoredDocument doc = dueDocument();
        when(documentRepository.findAllByStatusAndIndexStatusIn(eq(DocumentStatus.READY), any()))
                .thenReturn(List.of(doc));
        doThrow(new RuntimeException("boom")).when(indexingService).indexDocument(doc);
        IndexingJob job = new IndexingJob(documentRepository, indexingService);

        job.processDue();

        verify(indexingService, times(1)).indexDocument(doc);
    }

    private static StoredDocument dueDocument() {
        return StoredDocument.builder()
                .id(UUID.randomUUID())
                .docSetId(UUID.randomUUID())
                .filename("a.txt")
                .contentType("text/plain")
                .contentLength(1L)
                .sha256("s")
                .content("x".getBytes())
                .extractedText("x")
                .status(DocumentStatus.READY)
                .createdAt(Instant.now())
                .build();
    }
}
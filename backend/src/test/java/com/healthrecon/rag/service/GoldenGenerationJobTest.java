package com.healthrecon.rag.service;

import com.healthrecon.rag.config.GoldenProperties;
import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.GoldenStatus;
import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldenGenerationJobTest {

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private GoldenCaseGenerator goldenCaseGenerator;

    private GoldenGenerationJob job;

    @BeforeEach
    void setUp() {
        job = new GoldenGenerationJob(
                new GoldenProperties(true, 60000, 0, 3, 12000, 3),
                documentRepository, goldenCaseGenerator);
    }

    @Test
    void skipsWhenDisabled() {
        GoldenGenerationJob disabled = new GoldenGenerationJob(
                new GoldenProperties(false, 60000, 0, 3, 12000, 3),
                documentRepository, goldenCaseGenerator);
        disabled.processPending();

        verify(documentRepository, never()).findAllByStatusAndIndexStatusInAndGoldenStatusIn(any(), any(), any());
    }

    @Test
    void processesPendingDocuments() {
        when(documentRepository.findAllByStatusAndIndexStatusInAndGoldenStatusIn(any(), any(), any()))
                .thenReturn(List.of(readyDoc(GoldenStatus.PENDING)));

        job.processPending();

        verify(goldenCaseGenerator).generate(any(StoredDocument.class));
    }

    @Test
    void retriesCrashedDocument() {
        when(documentRepository.findAllByStatusAndIndexStatusInAndGoldenStatusIn(any(), any(), any()))
                .thenReturn(List.of(readyDoc(GoldenStatus.GENERATING)));

        job.processPending();

        verify(goldenCaseGenerator).generate(any(StoredDocument.class));
    }

    @Test
    void skipsDocumentExceedingRetryCap() {
        when(documentRepository.findAllByStatusAndIndexStatusInAndGoldenStatusIn(any(), any(), any()))
                .thenReturn(List.of(readyDocFailed(4)));

        job.processPending();

        verify(goldenCaseGenerator, never()).generate(any());
    }

    @Test
    void retriesDocumentUnderRetryCap() {
        when(documentRepository.findAllByStatusAndIndexStatusInAndGoldenStatusIn(any(), any(), any()))
                .thenReturn(List.of(readyDocFailed(1)));

        job.processPending();

        verify(goldenCaseGenerator).generate(any(StoredDocument.class));
    }

    @Test
    void noDueDocumentsNoInteraction() {
        when(documentRepository.findAllByStatusAndIndexStatusInAndGoldenStatusIn(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        job.processPending();

        verify(goldenCaseGenerator, never()).generate(any());
    }

    private static StoredDocument readyDoc(GoldenStatus status) {
        StoredDocument doc = baseDoc();
        if (status == GoldenStatus.GENERATING) {
            doc.markGoldenGenerating();
        }
        return doc;
    }

    private static StoredDocument readyDocFailed(int attempts) {
        StoredDocument doc = baseDoc();
        for (int i = 0; i < attempts; i++) {
            doc.markGoldenGenerating();
        }
        doc.markGoldenFailed("boom");
        return doc;
    }

    private static StoredDocument baseDoc() {
        return StoredDocument.builder()
                .id(UUID.randomUUID())
                .docSetId(UUID.randomUUID())
                .filename("doc.md")
                .contentLength(0)
                .sha256("x")
                .content(new byte[0])
                .status(DocumentStatus.READY)
                .indexStatus(IndexStatus.INDEXED)
                .createdAt(Instant.now())
                .build();
    }
}
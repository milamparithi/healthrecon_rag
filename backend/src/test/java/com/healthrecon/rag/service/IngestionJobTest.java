package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionJobTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private TextExtractionService textExtractionService;

    @Mock
    private DocumentSetService documentSetService;

    @Test
    void processesPendingDocumentsToReady() {
        StoredDocument doc = pendingDocument("notes.txt", "Extracted content");
        when(documentRepository.findAllByStatus(DocumentStatus.PENDING)).thenReturn(List.of(doc));
        when(textExtractionService.extract(doc.getContent())).thenReturn("Extracted content");
        IngestionJob job = new IngestionJob(documentRepository, textExtractionService, documentSetService);

        job.processPending();

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(doc.getExtractedText()).isEqualTo("Extracted content");
        assertThat(doc.getError()).isNull();
        verify(documentRepository, times(2)).save(doc);
        verify(documentSetService).recomputeStatus(doc.getDocSetId());
    }

    @Test
    void marksFailedWhenExtractionThrows() {
        StoredDocument doc = pendingDocument("bad.txt", "data");
        when(documentRepository.findAllByStatus(DocumentStatus.PENDING)).thenReturn(List.of(doc));
        doThrow(new IllegalArgumentException("broken")).when(textExtractionService).extract(doc.getContent());
        IngestionJob job = new IngestionJob(documentRepository, textExtractionService, documentSetService);

        job.processPending();

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(doc.getError()).contains("broken");
        verify(documentRepository, times(2)).save(doc);
    }

    private static StoredDocument pendingDocument(String filename, String content) {
        return StoredDocument.builder()
                .id(UUID.randomUUID())
                .docSetId(UUID.randomUUID())
                .filename(filename)
                .contentType("text/plain")
                .contentLength(content.length())
                .sha256("abc")
                .content(content.getBytes(StandardCharsets.UTF_8))
                .status(DocumentStatus.PENDING)
                .createdAt(Instant.now())
                .build();
    }
}
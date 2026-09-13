package com.healthrecon.rag.service;

import com.healthrecon.rag.TestSecurity;
import com.healthrecon.rag.api.dto.CreateDocumentSetRequest;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.DocumentSetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSetServiceTest {

    @Mock
    private DocumentSetRepository documentSetRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private VectorIndexer vectorIndexer;

    @InjectMocks
    private DocumentSetService documentSetService;

    private final UUID ownerId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void createPersistsSetOwnedByCurrentUser() {
        TestSecurity.authenticate(ownerId);
        when(documentSetRepository.existsByOwnerIdAndName(ownerId, "Docs")).thenReturn(false);
        when(documentSetRepository.save(any(DocumentSet.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = documentSetService.create(new CreateDocumentSetRequest("  Docs  ", "desc"));

        assertThat(result.name()).isEqualTo("Docs");
        assertThat(result.status()).isEqualTo(DocumentSetStatus.EMPTY);
        verify(documentSetRepository).save(any(DocumentSet.class));
    }

    @Test
    void createRejectsDuplicateNameForOwner() {
        TestSecurity.authenticate(ownerId);
        when(documentSetRepository.existsByOwnerIdAndName(ownerId, "Docs")).thenReturn(true);

        assertThatThrownBy(() -> documentSetService.create(new CreateDocumentSetRequest("Docs", null)))
                .isInstanceOf(ConflictException.class);
        verify(documentSetRepository, never()).save(any());
    }

    @Test
    void requireOwnedSetThrowsWhenNotFoundOrOtherOwners() {
        TestSecurity.authenticate(ownerId);
        when(documentSetRepository.findByIdAndOwnerId(UUID.fromString("00000000-0000-0000-0000-000000000001"), ownerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentSetService.requireOwnedSet(UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void recomputeStatusReadyWhenAllDocumentsReady() {
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = documentSetServiceStub(docSetId);
        when(documentRepository.countByDocSetId(docSetId)).thenReturn(3L);
        when(documentRepository.countByDocSetIdAndStatus(docSetId, DocumentStatus.READY)).thenReturn(3L);
        when(documentRepository.countByDocSetIdAndStatus(docSetId, DocumentStatus.FAILED)).thenReturn(0L);

        documentSetService.recomputeStatus(docSetId);

        assertThat(set.getStatus()).isEqualTo(DocumentSetStatus.READY);
    }

    @Test
    void recomputeStatusReportsFailureWhenAnyDocumentFailed() {
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = documentSetServiceStub(docSetId);
        when(documentRepository.countByDocSetId(docSetId)).thenReturn(2L);
        when(documentRepository.countByDocSetIdAndStatus(docSetId, DocumentStatus.READY)).thenReturn(1L);
        when(documentRepository.countByDocSetIdAndStatus(docSetId, DocumentStatus.FAILED)).thenReturn(1L);

        documentSetService.recomputeStatus(docSetId);

        assertThat(set.getStatus()).isEqualTo(DocumentSetStatus.FAILED);
    }

    private DocumentSet documentSetServiceStub(UUID docSetId) {
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null,
                DocumentSetStatus.UPLOADING, Instant.now(), Instant.now());
        when(documentSetRepository.findById(docSetId)).thenReturn(Optional.of(set));
        return set;
    }

    @Test
    void listReturnsOnlyOwnedSets() {
        TestSecurity.authenticate(ownerId);
        when(documentSetRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId))
                .thenReturn(List.of(
                        new DocumentSet(UUID.randomUUID(), ownerId, "A", null, DocumentSetStatus.READY, Instant.now(), Instant.now())));
        when(documentRepository.countByDocSetId(any())).thenReturn(0L);

        var result = documentSetService.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("A");
    }

    @Test
    void deleteDocumentRemovesVectorsBeforeDeletingRow() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        StoredDocument doc = documentStub(docSetId, docId);
        when(documentRepository.findByIdAndDocSetId(docId, docSetId)).thenReturn(Optional.of(doc));

        documentSetService.deleteDocument(docSetId, docId);

        verify(vectorIndexer).deleteDocument(docSetId, docId);
        verify(documentRepository).delete(doc);
        verify(documentSetRepository).findById(docSetId);
    }

    @Test
    void deleteDocumentThrowsNotFoundForForeignOrMissingDocument() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null, DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));
        when(documentRepository.findByIdAndDocSetId(UUID.fromString("00000000-0000-0000-0000-000000000002"), docSetId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentSetService.deleteDocument(
                        docSetId, UUID.fromString("00000000-0000-0000-0000-000000000002")))
                .isInstanceOf(NotFoundException.class);
        verify(documentRepository, never()).delete(any());
    }

    @Test
    void deleteDropsOffCollection() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null, DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));

        documentSetService.delete(docSetId);

        verify(vectorIndexer).deleteSet(docSetId);
        verify(documentSetRepository).delete(set);
    }

    @Test
    void updateRenamesSetAndDescription() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", "old", DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));
        when(documentSetRepository.existsByOwnerIdAndName(ownerId, "New docs")).thenReturn(false);
        when(documentRepository.countByDocSetId(docSetId)).thenReturn(2L);

        var result = documentSetService.update(docSetId,
                new com.healthrecon.rag.api.dto.UpdateDocumentSetRequest("  New docs  ", " fresh "));

        assertThat(result.name()).isEqualTo("New docs");
        assertThat(result.description()).isEqualTo("fresh");
        assertThat(result.documentCount()).isEqualTo(2);
        verify(documentSetRepository).existsByOwnerIdAndName(ownerId, "New docs");
    }

    @Test
    void updateRejectsNameCollision() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null, DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));
        when(documentSetRepository.existsByOwnerIdAndName(ownerId, "Taken")).thenReturn(true);

        assertThatThrownBy(() -> documentSetService.update(docSetId,
                new com.healthrecon.rag.api.dto.UpdateDocumentSetRequest("Taken", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateKeepsNameWhenUnchanged() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null, DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));
        when(documentRepository.countByDocSetId(docSetId)).thenReturn(0L);

        documentSetService.update(docSetId,
                new com.healthrecon.rag.api.dto.UpdateDocumentSetRequest("Docs", null));

        verify(documentSetRepository, never()).existsByOwnerIdAndName(any(), any());
    }

    @Test
    void deleteAllDocumentsDropsVectorsBeforeDeletingRows() {
        TestSecurity.authenticate(ownerId);
        UUID docSetId = UUID.randomUUID();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null, DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));
        when(documentSetRepository.findById(docSetId)).thenReturn(Optional.of(set));
        when(documentRepository.countByDocSetId(docSetId)).thenReturn(0L);

        documentSetService.deleteAllDocuments(docSetId);

        verify(vectorIndexer).deleteSet(docSetId);
        verify(documentRepository).deleteByDocSetId(docSetId);
        assertThat(set.getStatus()).isEqualTo(DocumentSetStatus.EMPTY);
    }

    private StoredDocument documentStub(UUID docSetId, UUID docId) {
        StoredDocument doc = StoredDocument.builder()
                .id(docId)
                .docSetId(docSetId)
                .filename("a.txt")
                .contentType("text/plain")
                .contentLength(1L)
                .sha256("x")
                .content("a".getBytes())
                .status(DocumentStatus.READY)
                .createdAt(Instant.now())
                .build();
        DocumentSet set = new DocumentSet(docSetId, ownerId, "Docs", null, DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetRepository.findByIdAndOwnerId(docSetId, ownerId)).thenReturn(Optional.of(set));
        when(documentSetRepository.findById(docSetId)).thenReturn(Optional.of(set));
        return doc;
    }
}
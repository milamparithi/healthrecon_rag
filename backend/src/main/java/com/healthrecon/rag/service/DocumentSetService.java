package com.healthrecon.rag.service;

import com.healthrecon.rag.api.dto.CreateDocumentSetRequest;
import com.healthrecon.rag.api.dto.DocumentSetResponse;
import com.healthrecon.rag.api.dto.UpdateDocumentSetRequest;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.DocumentSetRepository;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentSetService {

    private final DocumentSetRepository documentSetRepository;
    private final DocumentRepository documentRepository;
    private final VectorIndexer vectorIndexer;

    public DocumentSetService(DocumentSetRepository documentSetRepository,
                              DocumentRepository documentRepository,
                              VectorIndexer vectorIndexer) {
        this.documentSetRepository = documentSetRepository;
        this.documentRepository = documentRepository;
        this.vectorIndexer = vectorIndexer;
    }

    @Transactional
    public DocumentSetResponse create(CreateDocumentSetRequest request) {
        CurrentUser user = CurrentUserSupport.require();
        String name = request.name().trim();
        if (documentSetRepository.existsByOwnerIdAndName(user.id(), name)) {
            throw new ConflictException("A document set named '" + name + "' already exists");
        }
        Instant now = Instant.now();
        DocumentSet set = new DocumentSet(UUID.randomUUID(), user.id(), name,
                request.description() == null ? null : request.description().trim(),
                DocumentSetStatus.EMPTY, now, now);
        return DocumentSetResponse.from(documentSetRepository.save(set), 0);
    }

    @Transactional(readOnly = true)
    public List<DocumentSetResponse> list() {
        CurrentUser user = CurrentUserSupport.require();
        return documentSetRepository.findAllByOwnerIdOrderByCreatedAtDesc(user.id()).stream()
                .map(set -> DocumentSetResponse.from(set, documentRepository.countByDocSetId(set.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentSet requireOwnedSet(UUID docSetId) {
        CurrentUser user = CurrentUserSupport.require();
        return documentSetRepository.findByIdAndOwnerId(docSetId, user.id())
                .orElseThrow(() -> new NotFoundException("Document set not found"));
    }

    @Transactional
    public void delete(UUID docSetId) {
        DocumentSet set = requireOwnedSet(docSetId);
        documentSetRepository.delete(set);
        vectorIndexer.deleteSet(docSetId);
    }

    @Transactional
    public DocumentSetResponse update(UUID docSetId, UpdateDocumentSetRequest request) {
        DocumentSet set = requireOwnedSet(docSetId);
        String newName = request.name().trim();
        if (!set.getName().equals(newName) && documentSetRepository.existsByOwnerIdAndName(set.getOwnerId(), newName)) {
            throw new ConflictException("A document set named '" + newName + "' already exists");
        }
        set.updateMetadata(newName,
                request.description() == null ? null : request.description().trim(), Instant.now());
        return DocumentSetResponse.from(set, documentRepository.countByDocSetId(docSetId));
    }

    @Transactional
    public void deleteAllDocuments(UUID docSetId) {
        requireOwnedSet(docSetId);
        vectorIndexer.deleteSet(docSetId);
        documentRepository.deleteByDocSetId(docSetId);
        recomputeStatus(docSetId);
    }

    @Transactional
    public void deleteDocument(UUID docSetId, UUID docId) {
        requireOwnedSet(docSetId);
        StoredDocument doc = documentRepository.findByIdAndDocSetId(docId, docSetId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        vectorIndexer.deleteDocument(docSetId, docId);
        documentRepository.delete(doc);
        recomputeStatus(docSetId);
    }

    /**
     * Recomputes the aggregate status of a document set from its documents.
     * No owner scoping: used by the ingestion worker.
     */
    @Transactional
    public void recomputeStatus(UUID docSetId) {
        DocumentSet set = documentSetRepository.findById(docSetId)
                .orElseThrow(() -> new NotFoundException("Document set not found"));
        long total = documentRepository.countByDocSetId(docSetId);
        long ready = documentRepository.countByDocSetIdAndStatus(docSetId, DocumentStatus.READY);
        long failed = documentRepository.countByDocSetIdAndStatus(docSetId, DocumentStatus.FAILED);
        long inFlight = total - ready - failed;

        DocumentSetStatus status;
        if (inFlight > 0) {
            status = DocumentSetStatus.UPLOADING;
        } else if (total == 0) {
            status = DocumentSetStatus.EMPTY;
        } else if (failed > 0) {
            status = DocumentSetStatus.FAILED;
        } else {
            status = DocumentSetStatus.READY;
        }
        set.setStatus(status, Instant.now());
    }
}
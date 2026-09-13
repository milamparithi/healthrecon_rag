package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.CreateDocumentSetRequest;
import com.healthrecon.rag.api.dto.DocumentDetail;
import com.healthrecon.rag.api.dto.DocumentListItem;
import com.healthrecon.rag.api.dto.DocumentSetResponse;
import com.healthrecon.rag.api.dto.PageResponse;
import com.healthrecon.rag.api.dto.UpdateDocumentSetRequest;
import com.healthrecon.rag.api.dto.UploadResult;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.service.DocumentSetService;
import com.healthrecon.rag.service.DocumentUploadService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documentsets")
public class DocumentSetController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentSetService documentSetService;
    private final DocumentUploadService documentUploadService;
    private final DocumentRepository documentRepository;

    public DocumentSetController(DocumentSetService documentSetService,
                                 DocumentUploadService documentUploadService,
                                 DocumentRepository documentRepository) {
        this.documentSetService = documentSetService;
        this.documentUploadService = documentUploadService;
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public List<DocumentSetResponse> list() {
        return documentSetService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentSetResponse create(@Valid @RequestBody CreateDocumentSetRequest request) {
        return documentSetService.create(request);
    }

    @GetMapping("/{id}")
    public DocumentSetResponse get(@PathVariable UUID id) {
        var set = documentSetService.requireOwnedSet(id);
        long count = documentRepository.countByDocSetId(set.getId());
        return DocumentSetResponse.from(set, count);
    }

    @PatchMapping("/{id}")
    public DocumentSetResponse update(@PathVariable UUID id,
                                      @Valid @RequestBody UpdateDocumentSetRequest request) {
        return documentSetService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        documentSetService.delete(id);
    }

    @GetMapping("/{id}/documents")
    public PageResponse<DocumentListItem> listDocuments(@PathVariable UUID id,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        documentSetService.requireOwnedSet(id);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.from(documentRepository.listByDocSetId(id, PageRequest.of(safePage, safeSize))
                .map(DocumentListItem::from));
    }

    @PostMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public List<UploadResult> upload(@PathVariable UUID id,
                                     @RequestParam("files") MultipartFile[] files) {
        List<UploadResult> results = documentUploadService.upload(id, files);
        documentSetService.recomputeStatus(id);
        return results;
    }

    @DeleteMapping("/{id}/documents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAllDocuments(@PathVariable UUID id) {
        documentSetService.deleteAllDocuments(id);
    }

    @GetMapping("/{id}/documents/{docId}")
    public DocumentDetail getDocument(@PathVariable UUID id, @PathVariable UUID docId) {
        documentSetService.requireOwnedSet(id);
        StoredDocument doc = documentRepository.findByIdAndDocSetId(docId, id)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        return new DocumentDetail(doc.getId(), doc.getFilename(), doc.getContentType(),
                doc.getContentLength(), doc.getStatus(), doc.getError(),
                doc.getExtractedText(), doc.getExtractedText() == null ? 0 : doc.getExtractedText().length(),
                doc.getCreatedAt());
    }

    @DeleteMapping("/{id}/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable UUID id, @PathVariable UUID docId) {
        documentSetService.deleteDocument(id, docId);
    }
}
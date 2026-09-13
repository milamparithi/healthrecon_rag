package com.healthrecon.rag.service;

import com.healthrecon.rag.api.dto.UploadResult;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.DocumentSetRepository;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentUploadService {

    private final DocumentSetRepository documentSetRepository;
    private final DocumentRepository documentRepository;
    private final QuotaService quotaService;

    public DocumentUploadService(DocumentSetRepository documentSetRepository,
                                 DocumentRepository documentRepository,
                                 QuotaService quotaService) {
        this.documentSetRepository = documentSetRepository;
        this.documentRepository = documentRepository;
        this.quotaService = quotaService;
    }

    /**
     * Stores new documents as PENDING. Extraction is picked up asynchronously by the IngestionJob poller.
     * Duplicate content within the same document set is skipped. Files are accepted only while they fit
     * into the owner's storage quota; duplicates never consume quota.
     */
    @Transactional
    public List<UploadResult> upload(UUID docSetId, MultipartFile[] files) {
        CurrentUser user = CurrentUserSupport.require();
        var set = documentSetRepository.findByIdAndOwnerId(docSetId, user.id())
                .orElseThrow(() -> new NotFoundException("Document set not found"));

        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("No files provided");
        }

        QuotaTracker quota = new QuotaTracker(quotaService.getUsedBytes(user.id()), quotaService.getLimitBytes());
        List<UploadResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(storeFile(set.getId(), file, quota));
        }

        set.setStatus(DocumentSetStatus.UPLOADING, Instant.now());
        return results;
    }

    private UploadResult storeFile(UUID docSetId, MultipartFile file, QuotaTracker quota) {
        if (file.isEmpty()) {
            return UploadResult.failed(file.getOriginalFilename(), "File is empty");
        }
        try {
            byte[] content = file.getBytes();
            String sha256 = sha256Hex(content);
            if (documentRepository.existsByDocSetIdAndSha256(docSetId, sha256)) {
                return UploadResult.duplicate(file.getOriginalFilename());
            }
            if (!quota.canAccept(content.length)) {
                return UploadResult.failed(file.getOriginalFilename(), quotaExceededMessage(quota));
            }
            quota.accept(content.length);
            StoredDocument doc = StoredDocument.builder()
                    .id(UUID.randomUUID())
                    .docSetId(docSetId)
                    .filename(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .contentLength(content.length)
                    .sha256(sha256)
                    .content(content)
                    .status(DocumentStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
            documentRepository.save(doc);
            return UploadResult.uploaded(doc.getId(), doc.getFilename());
        } catch (Exception e) {
            return UploadResult.failed(file.getOriginalFilename(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String quotaExceededMessage(QuotaTracker quota) {
        return "Storage quota exceeded (%.1f MB used of %.1f MB)"
                .formatted(quota.used / (1024.0 * 1024.0), quota.limit / (1024.0 * 1024.0));
    }

    private static String sha256Hex(byte[] content) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        return HexFormat.of().formatHex(hash);
    }

    private static final class QuotaTracker {
        private long used;
        private final long limit;

        private QuotaTracker(long used, long limit) {
            this.used = used;
            this.limit = limit;
        }

        private boolean canAccept(long additionalBytes) {
            return used + additionalBytes <= limit;
        }

        private void accept(long additionalBytes) {
            this.used += additionalBytes;
        }
    }
}
package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls for documents awaiting extraction, parses them with Tika and stores the
 * extracted text. Simple in-app worker (no external queue) with crash recovery:
 * PENDING documents are picked up again after a restart.
 */
@Component
public class IngestionJob {

    private static final Logger log = LoggerFactory.getLogger(IngestionJob.class);

    private final DocumentRepository documentRepository;
    private final TextExtractionService textExtractionService;
    private final DocumentSetService documentSetService;

    public IngestionJob(DocumentRepository documentRepository,
                        TextExtractionService textExtractionService,
                        DocumentSetService documentSetService) {
        this.documentRepository = documentRepository;
        this.textExtractionService = textExtractionService;
        this.documentSetService = documentSetService;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.poll-ms:5000}",
            initialDelayString = "${app.ingestion.initial-delay-ms:5000}")
    public void processPending() {
        List<StoredDocument> pending = documentRepository.findAllByStatus(DocumentStatus.PENDING);
        for (StoredDocument document : pending) {
            processDocument(document);
            documentSetService.recomputeStatus(document.getDocSetId());
        }
    }

    void processDocument(StoredDocument document) {
        document.markExtracting(Instant.now());
        documentRepository.save(document);
        try {
            String text = textExtractionService.extract(document.getContent());
            document.markReady(text);
        } catch (Exception e) {
            log.warn("Extraction failed for document {} ({})", document.getId(), document.getFilename(), e);
            document.markFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        documentRepository.save(document);
    }
}
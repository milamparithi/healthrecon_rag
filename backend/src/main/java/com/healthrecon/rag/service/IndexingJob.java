package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls for READY documents that still need vector indexing (or were left in
 * INDEXING after a crash) and processes them. Idempotent upserts make re-runs
 * safe. Documents that failed are not retried automatically.
 */
@Component
public class IndexingJob {

    private static final Logger log = LoggerFactory.getLogger(IndexingJob.class);

    private final DocumentRepository documentRepository;
    private final IndexingService indexingService;

    public IndexingJob(DocumentRepository documentRepository, IndexingService indexingService) {
        this.documentRepository = documentRepository;
        this.indexingService = indexingService;
    }

    @Scheduled(fixedDelayString = "${app.indexing.poll-ms:5000}", initialDelayString = "${app.indexing.initial-delay-ms:5000}")
    public void processDue() {
        List<StoredDocument> due = documentRepository.findAllByStatusAndIndexStatusIn(
                DocumentStatus.READY, List.of(IndexStatus.NOT_INDEXED, IndexStatus.INDEXING));
        for (StoredDocument document : due) {
            try {
                indexingService.indexDocument(document);
            } catch (Exception e) {
                log.warn("Indexing job failed for document {} ({})", document.getId(), document.getFilename(), e);
            }
        }
    }
}
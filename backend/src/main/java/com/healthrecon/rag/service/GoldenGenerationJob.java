package com.healthrecon.rag.service;

import com.healthrecon.rag.config.GoldenProperties;
import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.GoldenStatus;
import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls for indexed documents that still need golden Q&A generation and
 * processes them. Crash-recoverable: documents left in GENERATING are picked up
 * again. Documents that exceeded the retry cap are left FAILED and skipped.
 * The whole job is gated by {@code app.golden.enabled}.
 */
@Component
public class GoldenGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(GoldenGenerationJob.class);

    private final GoldenProperties properties;
    private final DocumentRepository documentRepository;
    private final GoldenCaseGenerator goldenCaseGenerator;

    public GoldenGenerationJob(GoldenProperties properties,
                               DocumentRepository documentRepository,
                               GoldenCaseGenerator goldenCaseGenerator) {
        this.properties = properties;
        this.documentRepository = documentRepository;
        this.goldenCaseGenerator = goldenCaseGenerator;
    }

    @Scheduled(fixedDelayString = "${app.golden.poll-ms:60000}",
            initialDelayString = "${app.golden.initial-delay-ms:10000}")
    public void processPending() {
        if (!properties.enabled()) {
            return;
        }
        List<StoredDocument> due = documentRepository.findAllByStatusAndIndexStatusInAndGoldenStatusIn(
                DocumentStatus.READY,
                List.of(IndexStatus.INDEXED),
                List.of(GoldenStatus.PENDING, GoldenStatus.GENERATING, GoldenStatus.FAILED));
        for (StoredDocument document : due) {
            if (document.getGoldenStatus() == GoldenStatus.FAILED
                    && document.getGoldenAttempts() >= properties.maxRetries()) {
                log.warn("Golden generation for document {} ({}) exceeds retry cap; skipping",
                        document.getId(), document.getFilename());
                continue;
            }
            try {
                goldenCaseGenerator.generate(document);
            } catch (Exception e) {
                log.warn("Golden generation job failed for document {} ({})",
                        document.getId(), document.getFilename(), e);
            }
        }
    }
}
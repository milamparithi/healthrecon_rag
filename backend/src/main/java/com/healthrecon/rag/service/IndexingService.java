package com.healthrecon.rag.service;

import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.service.chunking.ChunkingService;
import com.healthrecon.rag.service.chunking.TextChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Chunks and embeds a READY document and upserts the vectors. Indexing is
 * synchronous from this service's perspective; crash recovery is handled by
 * {@link IndexingJob} re-processing INDEXING documents (upsert is idempotent).
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final DocumentRepository documentRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingModel embeddingModel;
    private final VectorIndexer vectorIndexer;

    public IndexingService(DocumentRepository documentRepository,
                           ChunkingService chunkingService,
                           EmbeddingModel embeddingModel,
                           VectorIndexer vectorIndexer) {
        this.documentRepository = documentRepository;
        this.chunkingService = chunkingService;
        this.embeddingModel = embeddingModel;
        this.vectorIndexer = vectorIndexer;
    }

    @Transactional
    public void indexDocument(StoredDocument doc) {
        doc.markIndexing(Instant.now());
        documentRepository.save(doc);
        try {
            List<TextChunk> chunks = chunkingService.chunk(
                    doc.getId(), doc.getFilename(), doc.getContent(), doc.getExtractedText());
            if (!chunks.isEmpty()) {
                List<TextSegment> segments = chunks.stream()
                        .map(chunk -> QdrantVectorIndexer.segmentFor(doc, chunk))
                        .toList();
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                vectorIndexer.upsert(doc, chunks, segments, embeddings);
            }
            doc.markIndexed();
        } catch (Exception e) {
            log.warn("Indexing failed for document {} ({})", doc.getId(), doc.getFilename(), e);
            doc.markIndexFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        documentRepository.save(doc);
    }
}
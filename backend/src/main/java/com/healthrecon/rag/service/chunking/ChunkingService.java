package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.service.extraction.StructuralDocument;
import com.healthrecon.rag.service.extraction.StructureExtractionService;
import com.healthrecon.rag.service.extraction.StructureExtractor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Chooses the chunking strategy. In {@code adaptive} mode the document
 * structure is extracted from the raw content (headings preserved) and chunks
 * are formed around sections; in {@code fixed} mode plain character windows are
 * used. Binary formats fall back to plain text either way.
 */
@Service
public class ChunkingService {

    private final RagProperties properties;
    private final RagProperties.Chunking config;
    private final StructureExtractionService structureExtractionService;
    private final AdaptiveStructuralChunker adaptiveChunker;
    private final FixedCharChunker fixedChunker;

    public ChunkingService(RagProperties properties,
                           StructureExtractionService structureExtractionService,
                           AdaptiveStructuralChunker adaptiveChunker,
                           FixedCharChunker fixedChunker) {
        this.properties = properties;
        this.config = properties.chunking();
        this.structureExtractionService = structureExtractionService;
        this.adaptiveChunker = adaptiveChunker;
        this.fixedChunker = fixedChunker;
    }

    public List<TextChunk> chunk(UUID docId, String filename, byte[] content, String extractedText) {
        if ("fixed".equalsIgnoreCase(config.mode())) {
            return fixedChunker.chunk(docId, StructureExtractor.fromPlainText(extractedText));
        }
        StructuralDocument document = structureExtractionService.extract(filename, content, extractedText);
        if (document.totalCharacters() == 0) {
            return List.of();
        }
        return adaptiveChunker.chunk(docId, document);
    }
}
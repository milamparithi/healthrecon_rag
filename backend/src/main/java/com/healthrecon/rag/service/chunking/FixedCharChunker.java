package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.service.extraction.StructuralDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits a flat text by fixed character windows with a configurable overlap.
 * Used by the fixed chunking mode and as the fallback for pathological
 * section-less documents.
 */
@Component
public class FixedCharChunker implements ChunkingStrategy {

    private final RagProperties.Chunking config;

    public FixedCharChunker(RagProperties properties) {
        this.config = properties.chunking();
    }

    @Override
    public List<TextChunk> chunk(UUID docId, StructuralDocument document) {
        StringBuilder joined = new StringBuilder();
        for (var section : document.sections()) {
            if (!section.isHeading()) {
                joined.append(section.text()).append("\n\n");
            }
        }
        return split(docId, joined.toString().strip());
    }

    public List<TextChunk> split(UUID docId, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, config.chunkSize());
        int overlap = config.chunkOverlap();
        if (overlap >= size) {
            overlap = size - 1;
        }
        List<TextChunk> chunks = new ArrayList<>();
        int index = 0;
        int start = 0;
        while (start < text.length() && index < config.maxChunksPerDoc()) {
            int end = Math.min(start + size, text.length());
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(new TextChunk(docId, index, "", piece));
                index++;
            }
            if (end >= text.length()) {
                break;
            }
            int next = end - overlap;
            start = next <= start ? end : next;
        }
        return chunks;
    }
}
package com.healthrecon.rag.service.chunking;

import com.healthrecon.rag.service.extraction.StructuralDocument;

import java.util.List;
import java.util.UUID;

public interface ChunkingStrategy {

    List<TextChunk> chunk(UUID docId, StructuralDocument document);
}
package com.healthrecon.rag.service.chunking;

import java.util.UUID;

/**
 * A unit of text ready to be embedded. {@code section} holds the heading chain
 * the text belongs to and {@code text} the full text including the heading
 * chain prefix (what actually gets embedded).
 */
public record TextChunk(UUID docId, int chunkIndex, String section, String text) {
}
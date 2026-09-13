package com.healthrecon.rag.service;

import java.util.UUID;

public record ChunkSearchHit(UUID docId, String filename, String section, double score, String snippet) {
}
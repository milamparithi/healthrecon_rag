package com.healthrecon.rag.api.dto;

import java.util.UUID;

public record SourceResponse(UUID docId, String filename, String section, String snippet) {
}
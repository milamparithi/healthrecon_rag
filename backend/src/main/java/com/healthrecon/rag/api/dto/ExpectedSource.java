package com.healthrecon.rag.api.dto;

import java.util.UUID;

public record ExpectedSource(String filename, UUID docId, String section) {
}
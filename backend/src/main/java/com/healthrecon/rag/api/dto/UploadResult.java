package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.StoredDocument;

public record UploadResult(
        String filename,
        String status,
        java.util.UUID docId,
        String message) {

    public static UploadResult uploaded(java.util.UUID docId, String filename) {
        return new UploadResult(filename, "UPLOADED", docId, null);
    }

    public static UploadResult duplicate(String filename) {
        return new UploadResult(filename, "DUPLICATE", null, "A file with identical content already exists in this document set");
    }

    public static UploadResult failed(String filename, String message) {
        return new UploadResult(filename, "FAILED", null, message);
    }
}
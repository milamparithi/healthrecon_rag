package com.healthrecon.rag.api.dto;

public record QuotaResponse(long usedBytes, long limitBytes) {
}
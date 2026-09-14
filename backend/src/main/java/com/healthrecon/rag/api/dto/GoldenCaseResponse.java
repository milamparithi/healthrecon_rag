package com.healthrecon.rag.api.dto;

import com.healthrecon.rag.domain.GoldenCase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GoldenCaseResponse(
        UUID id,
        UUID docSetId,
        UUID sourceDocId,
        String question,
        String referenceAnswer,
        List<ExpectedSource> expectedSources,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static GoldenCaseResponse from(GoldenCase goldenCase, List<ExpectedSource> expectedSources) {
        return new GoldenCaseResponse(
                goldenCase.getId(),
                goldenCase.getDocSetId(),
                goldenCase.getSourceDocId(),
                goldenCase.getQuestion(),
                goldenCase.getReferenceAnswer(),
                expectedSources,
                goldenCase.getStatus(),
                goldenCase.getCreatedAt(),
                goldenCase.getUpdatedAt());
    }
}
package com.healthrecon.rag.api.dto;

public record EvalMetricsResponse(
        long totalCaptured,
        long flagged,
        long sampled,
        long pending,
        long reviewed,
        long dismissed,
        long accepted,
        long reworded,
        long rejected,
        Double averageRating) {
}
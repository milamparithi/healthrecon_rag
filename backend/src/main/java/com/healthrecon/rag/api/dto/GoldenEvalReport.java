package com.healthrecon.rag.api.dto;

import java.util.List;

public record GoldenEvalReport(
        int casesEvaluated,
        double recallAtK,
        double mrr,
        double hitRate,
        List<CaseEvalResult> cases,
        List<String> warnings) {
}
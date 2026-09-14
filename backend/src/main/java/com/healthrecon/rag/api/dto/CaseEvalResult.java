package com.healthrecon.rag.api.dto;

import java.util.List;

public record CaseEvalResult(
        String question,
        List<String> expected,
        List<String> retrieved,
        boolean hit,
        int rank,
        double recall) {
}
package com.healthrecon.rag.api.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.domain.AnswerEval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnswerEvalResponse(
        UUID id,
        UUID docSetId,
        UUID chatMessageId,
        UUID conversationId,
        String question,
        String answer,
        List<SourceResponse> sources,
        Double coverageScore,
        List<String> autoFlags,
        boolean sampled,
        String origin,
        String reviewStatus,
        String verdict,
        Integer rating,
        String comment,
        String correctedAnswer,
        Instant reviewedAt,
        Instant createdAt) {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvalResponse.class);
    private static final TypeReference<List<SourceResponse>> SOURCES_TYPE = new TypeReference<>() {
    };

    public static AnswerEvalResponse from(AnswerEval eval, ObjectMapper objectMapper) {
        return new AnswerEvalResponse(
                eval.getId(),
                eval.getDocSetId(),
                eval.getChatMessageId(),
                eval.getConversationId(),
                eval.getQuestion(),
                eval.getAnswer(),
                parseSources(eval.getSources(), objectMapper),
                eval.getCoverageScore(),
                eval.flags(),
                eval.isSampled(),
                eval.getOrigin(),
                eval.getReviewStatus(),
                eval.getVerdict(),
                eval.getRating(),
                eval.getComment(),
                eval.getCorrectedAnswer(),
                eval.getReviewedAt(),
                eval.getCreatedAt());
    }

    private static List<SourceResponse> parseSources(String sources, ObjectMapper objectMapper) {
        if (sources == null || sources.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sources, SOURCES_TYPE);
        } catch (Exception e) {
            log.warn("Could not parse eval sources", e);
            return List.of();
        }
    }
}
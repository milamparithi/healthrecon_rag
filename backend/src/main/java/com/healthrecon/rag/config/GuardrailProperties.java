package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Safety guard rails for the RAG chat. All checks are deterministic and narrow so
 * genuine document questions keep working; offenders get a fixed safe refusal.
 */
@ConfigurationProperties(prefix = "app.rag.guardrails")
public record GuardrailProperties(
        boolean enabled,
        int maxInputChars,
        boolean requireCitation,
        double hallucinationMinCoverage,
        int rateLimitRequests,
        int rateLimitWindowSeconds,
        int maxMessagesPerConversation,
        boolean llmCheckEnabled,
        List<String> harmPhrases,
        List<String> injectionPhrases,
        List<String> rudePhrases,
        List<String> alarmistPhrases) {
}
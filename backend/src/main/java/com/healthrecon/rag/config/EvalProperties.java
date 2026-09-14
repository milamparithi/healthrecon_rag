package com.healthrecon.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Output-evaluation and human-in-the-loop review tunables. Every grounded chat
 * answer is captured as an {@code answer_eval} row; flagged answers (guardrail
 * refusal, low grounding coverage, no sources) and a random sample of the rest
 * are queued for human review at the given rate.
 */
@ConfigurationProperties(prefix = "app.eval")
public record EvalProperties(
        boolean enabled,
        double sampleRate) {

    @Override
    public double sampleRate() {
        return Math.max(0.0, Math.min(1.0, sampleRate));
    }
}
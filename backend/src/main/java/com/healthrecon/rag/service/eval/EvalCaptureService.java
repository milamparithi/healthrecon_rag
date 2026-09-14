package com.healthrecon.rag.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.config.EvalProperties;
import com.healthrecon.rag.config.GuardrailProperties;
import com.healthrecon.rag.domain.AnswerEval;
import com.healthrecon.rag.repository.AnswerEvalRepository;
import com.healthrecon.rag.service.guardrails.ChatGuardrailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Captures every fresh grounded chat answer as an {@code answer_eval} row so
 * operators can audit output quality. Outputs that fail an automated check are
 * flagged for review; the rest are randomly sampled at
 * {@code app.eval.sample-rate} to keep a base-quality signal. Capture is
 * best-effort and never alters the chat response.
 */
@Service
public class EvalCaptureService {

    private static final Logger log = LoggerFactory.getLogger(EvalCaptureService.class);

    private final EvalProperties evalProperties;
    private final GuardrailProperties guardrailProperties;
    private final ChatGuardrailService guardrail;
    private final AnswerEvalRepository repository;
    private final ObjectMapper objectMapper;

    public EvalCaptureService(EvalProperties evalProperties,
                              GuardrailProperties guardrailProperties,
                              ChatGuardrailService guardrail,
                              AnswerEvalRepository repository,
                              ObjectMapper objectMapper) {
        this.evalProperties = evalProperties;
        this.guardrailProperties = guardrailProperties;
        this.guardrail = guardrail;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records a produced answer. {@code refused} indicates the answer was a
     * fixed guardrail refusal substituted for an original model output.
     * Concurrent calls are safe (no shared mutable state; sampling RNG is
     * thread-safe).
     */
    @Transactional
    public void capture(UUID docSetId, UUID chatMessageId, UUID conversationId,
                        String question, String answer, List<SourceResponse> sources,
                        String contextText, boolean refused) {
        if (!evalProperties.enabled()) {
            return;
        }
        List<String> flags = flagsFor(answer, sources, contextText, refused);
        double coverage = guardrail.coverageFor(answer, contextText);
        boolean sampled = flags.isEmpty() && evalProperties.sampleRate() > 0
                && Math.random() < evalProperties.sampleRate();
        repository.save(new AnswerEval(
                UUID.randomUUID(), docSetId, chatMessageId, conversationId,
                question, answer, sourcesJson(objectMapper, sources), AnswerEval.ORIGIN_CHAT,
                flags, coverage, sampled, Instant.now()));
    }

    private List<String> flagsFor(String answer, List<SourceResponse> sources, String contextText, boolean refused) {
        List<String> flags = new ArrayList<>();
        if (refused) {
            flags.add(AnswerEval.FLAG_GUARDRAIL_REFUSAL);
            return flags;
        }
        if (guardrailProperties.enabled()
                && guardrail.coverageFor(answer, contextText) < guardrailProperties.hallucinationMinCoverage()) {
            flags.add(AnswerEval.FLAG_LOW_COVERAGE);
        }
        if (sources == null || sources.isEmpty()) {
            flags.add(AnswerEval.FLAG_NO_SOURCES);
        }
        return flags;
    }

    private static String sourcesJson(ObjectMapper objectMapper, List<SourceResponse> sources) {
        try {
            return objectMapper.writeValueAsString(sources == null ? List.of() : sources);
        } catch (Exception e) {
            log.warn("Could not serialize eval sources", e);
            return "[]";
        }
    }
}
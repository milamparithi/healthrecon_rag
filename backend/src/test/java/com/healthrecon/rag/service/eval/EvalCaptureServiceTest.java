package com.healthrecon.rag.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.config.EvalProperties;
import com.healthrecon.rag.config.GuardrailProperties;
import com.healthrecon.rag.domain.AnswerEval;
import com.healthrecon.rag.repository.AnswerEvalRepository;
import com.healthrecon.rag.service.guardrails.ChatGuardrailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalCaptureServiceTest {

    @Mock
    private AnswerEvalRepository repository;
    @Mock
    private ChatGuardrailService guardrail;

    private final UUID docSetId = UUID.randomUUID();
    private final UUID chatMessageId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private static final GuardrailProperties DISABLED = new GuardrailProperties(
            false, 1000, true, 0.4, 60, 60, 300, false,
            List.of(), List.of(), List.of(), List.of());

    private static final GuardrailProperties ENABLED = new GuardrailProperties(
            true, 1000, true, 0.4, 60, 60, 300, false,
            List.of(), List.of(), List.of(), List.of());

    private EvalCaptureService service(EvalProperties properties, GuardrailProperties guardrailProperties) {
        return new EvalCaptureService(properties, guardrailProperties, guardrail, repository, new ObjectMapper());
    }

    @Test
    void noopWhenDisabled() {
        EvalCaptureService service = service(new EvalProperties(false, 0.1), DISABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "A", sources(), "context", false);

        verify(repository, never()).save(any());
    }

    @Test
    void capturesGroundedAnswerWithCoverageAndNoFlags() {
        when(guardrail.coverageFor(anyString(), anyString())).thenReturn(0.95);
        EvalCaptureService service = service(new EvalProperties(true, 0.0), DISABLED);

        service.capture(docSetId, chatMessageId, conversationId, "What dosage?", "Take 500mg daily.",
                sources(), "Take 500mg daily.", false);

        AnswerEval eval = captured();
        assertThat(eval.getDocSetId()).isEqualTo(docSetId);
        assertThat(eval.getChatMessageId()).isEqualTo(chatMessageId);
        assertThat(eval.getConversationId()).isEqualTo(conversationId);
        assertThat(eval.getQuestion()).isEqualTo("What dosage?");
        assertThat(eval.getAnswer()).isEqualTo("Take 500mg daily.");
        assertThat(eval.getOrigin()).isEqualTo(AnswerEval.ORIGIN_CHAT);
        assertThat(eval.flags()).isEmpty();
        assertThat(eval.getCoverageScore()).isEqualTo(0.95);
        assertThat(eval.isSampled()).isFalse();
        assertThat(eval.getReviewStatus()).isEqualTo(AnswerEval.REVIEW_PENDING);
    }

    @Test
    void flagsGuardrailRefusal() {
        EvalCaptureService service = service(new EvalProperties(true, 0.0), ENABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "answer", sources(), "context", true);

        assertThat(captured().flags()).containsExactly(AnswerEval.FLAG_GUARDRAIL_REFUSAL);
    }

    @Test
    void flagsLowCoverageWhenGuardrailsEnabledAndBelowThreshold() {
        when(guardrail.coverageFor(anyString(), anyString())).thenReturn(0.2);
        EvalCaptureService service = service(new EvalProperties(true, 0.0), ENABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "answer", sources(), "context", false);

        assertThat(captured().flags()).containsExactly(AnswerEval.FLAG_LOW_COVERAGE);
    }

    @Test
    void doesNotFlagLowCoverageWhenGuardrailsDisabled() {
        when(guardrail.coverageFor(anyString(), anyString())).thenReturn(0.0);
        EvalCaptureService service = service(new EvalProperties(true, 0.0), DISABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "answer", sources(), "context", false);

        assertThat(captured().flags()).isEmpty();
    }

    @Test
    void flagsMissingSources() {
        when(guardrail.coverageFor(anyString(), anyString())).thenReturn(0.95);
        EvalCaptureService service = service(new EvalProperties(true, 0.0), DISABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "answer", List.of(), "context", false);

        assertThat(captured().flags()).containsExactly(AnswerEval.FLAG_NO_SOURCES);
    }

    @Test
    void samplesUnflaggedAnswersAtRate() {
        when(guardrail.coverageFor(anyString(), anyString())).thenReturn(0.95);
        EvalCaptureService service = service(new EvalProperties(true, 1.0), DISABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "answer", sources(), "context", false);

        assertThat(captured().isSampled()).isTrue();
    }

    @Test
    void neverSamplesFlaggedAnswers() {
        EvalCaptureService service = service(new EvalProperties(true, 1.0), DISABLED);

        service.capture(docSetId, chatMessageId, conversationId, "Q", "answer", List.of(), "context", false);

        AnswerEval eval = captured();
        assertThat(eval.flags()).contains(AnswerEval.FLAG_NO_SOURCES);
        assertThat(eval.isSampled()).isFalse();
    }

    private AnswerEval captured() {
        ArgumentCaptor<AnswerEval> captor = ArgumentCaptor.forClass(AnswerEval.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static List<SourceResponse> sources() {
        return List.of(new SourceResponse(UUID.randomUUID(), "guide.md", "Dosage", "Take 500mg daily."));
    }
}
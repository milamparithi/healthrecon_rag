package com.healthrecon.rag.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.TestSecurity;
import com.healthrecon.rag.api.dto.AnswerEvalResponse;
import com.healthrecon.rag.api.dto.GoldenCaseResponse;
import com.healthrecon.rag.api.dto.ReviewRequest;
import com.healthrecon.rag.domain.AnswerEval;
import com.healthrecon.rag.domain.GoldenCase;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.AnswerEvalRepository;
import com.healthrecon.rag.service.DocumentSetService;
import com.healthrecon.rag.service.GoldenCaseService;
import com.healthrecon.rag.service.semanticcache.SemanticCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnswerEvalServiceTest {

    @Mock
    private AnswerEvalRepository evalRepository;
    @Mock
    private GoldenCaseService goldenCaseService;
    @Mock
    private DocumentSetService documentSetService;
    @Mock
    private SemanticCache semanticCache;

    private AnswerEvalService service;

    private final UUID docSetId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AnswerEvalService(evalRepository, goldenCaseService,
                documentSetService, semanticCache, new ObjectMapper());
        TestSecurity.authenticate(userId);
    }

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void getMapsStoredEval() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "What dosage?", "Take 500mg.",
                List.of(AnswerEval.FLAG_LOW_COVERAGE), true);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        AnswerEvalResponse response = service.get(docSetId, evalId);

        assertThat(response.question()).isEqualTo("What dosage?");
        assertThat(response.answer()).isEqualTo("Take 500mg.");
        assertThat(response.autoFlags()).containsExactly(AnswerEval.FLAG_LOW_COVERAGE);
        assertThat(response.sampled()).isTrue();
    }

    @Test
    void getThrowsNotFoundForForeignEval() {
        when(evalRepository.findByIdAndDocSetId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(docSetId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listDelegatesWithPagination() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(eval), PageRequest.of(0, 20), 1));

        Page<AnswerEvalResponse> page = service.list(docSetId, 0, 20, null, null, null);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo(evalId);
        verify(documentSetService).requireOwnedSet(docSetId);
    }

    @Test
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list(docSetId, 0, 20, "ARCHIVED", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(evalRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void reviewAcceptsWithoutEvictingCache() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        AnswerEvalResponse response = service.review(docSetId, evalId,
                new ReviewRequest(AnswerEval.VERDICT_ACCEPT, 5, "Great", null));

        assertThat(response.reviewStatus()).isEqualTo(AnswerEval.REVIEW_REVIEWED);
        assertThat(response.verdict()).isEqualTo(AnswerEval.VERDICT_ACCEPT);
        assertThat(response.rating()).isEqualTo(5);
        verify(semanticCache, never()).invalidate(any());
    }

    @Test
    void reviewRejectEvictsCache() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        service.review(docSetId, evalId,
                new ReviewRequest(AnswerEval.VERDICT_REJECT, 1, "Wrong", "Corrected answer."));

        verify(semanticCache).invalidate(docSetId);
    }

    @Test
    void reviewRejectRequiresCorrectedAnswer() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        assertThatThrownBy(() -> service.review(docSetId, evalId,
                new ReviewRequest(AnswerEval.VERDICT_REJECT, 1, "Wrong", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(semanticCache, never()).invalidate(any());
    }

    @Test
    void reviewRejectsUnknownVerdict() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        assertThatThrownBy(() -> service.review(docSetId, evalId,
                new ReviewRequest("ARCHIVE", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dismissMarksReviewedAt() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        service.dismiss(docSetId, evalId);

        assertThat(eval.getReviewStatus()).isEqualTo(AnswerEval.REVIEW_DISMISSED);
    }

    @Test
    void promoteWithoutCorrectedAnswerThrows() {
        UUID evalId = UUID.randomUUID();
        AnswerEval eval = eval(evalId, "Q", "A", List.of(), false);
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));

        assertThatThrownBy(() -> service.promote(docSetId, evalId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(goldenCaseService, never()).saveGenerated(any(), any(), any(), any(), any(), any());
    }

    @Test
    void promoteCreatesDraftGoldenCase() {
        UUID evalId = UUID.randomUUID();
        UUID sourceDocId = UUID.randomUUID();
        String sourcesJson = "[{\"docId\":\"" + sourceDocId + "\",\"filename\":\"guide.md\",\"section\":\"Dosage\",\"snippet\":\"Take 500mg.\"}]";
        AnswerEval eval = new AnswerEval(evalId, docSetId, UUID.randomUUID(), UUID.randomUUID(),
                "What dosage?", "wrong", sourcesJson, AnswerEval.ORIGIN_CHAT, List.of(), 0.9, false, Instant.now());
        eval.review(AnswerEval.VERDICT_REWORD, 2, "Too brief", "Take 500mg once daily.", Instant.now());
        when(evalRepository.findByIdAndDocSetId(evalId, docSetId)).thenReturn(Optional.of(eval));
        GoldenCase golden = new GoldenCase(UUID.randomUUID(), docSetId, userId, sourceDocId,
                "What dosage?", "Take 500mg once daily.", "[]", GoldenCase.STATUS_DRAFT,
                Instant.now(), Instant.now());
        when(goldenCaseService.saveGenerated(any(), any(), any(), any(), any(), any())).thenReturn(golden);

        GoldenCaseResponse response = service.promote(docSetId, evalId);

        assertThat(response.status()).isEqualTo(GoldenCase.STATUS_DRAFT);
        assertThat(response.question()).isEqualTo("What dosage?");
        assertThat(response.referenceAnswer()).isEqualTo("Take 500mg once daily.");
        assertThat(response.expectedSources()).hasSize(1);
        assertThat(eval.getReviewStatus()).isEqualTo(AnswerEval.REVIEW_REVIEWED);
        assertThat(eval.getVerdict()).isEqualTo(AnswerEval.VERDICT_REWORD);
    }

    @Test
    void metricsAggregatesCounts() {
        when(evalRepository.countByDocSetId(docSetId)).thenReturn(50L);
        when(evalRepository.countByDocSetIdAndAutoFlagsIsNotNull(docSetId)).thenReturn(6L);
        when(evalRepository.countByDocSetIdAndSampledTrue(docSetId)).thenReturn(4L);
        when(evalRepository.countByDocSetIdAndReviewStatus(docSetId, AnswerEval.REVIEW_PENDING)).thenReturn(30L);
        when(evalRepository.countByDocSetIdAndReviewStatus(docSetId, AnswerEval.REVIEW_REVIEWED)).thenReturn(18L);
        when(evalRepository.countByDocSetIdAndReviewStatus(docSetId, AnswerEval.REVIEW_DISMISSED)).thenReturn(2L);
        when(evalRepository.countByDocSetIdAndVerdict(docSetId, AnswerEval.VERDICT_ACCEPT)).thenReturn(12L);
        when(evalRepository.countByDocSetIdAndVerdict(docSetId, AnswerEval.VERDICT_REWORD)).thenReturn(4L);
        when(evalRepository.countByDocSetIdAndVerdict(docSetId, AnswerEval.VERDICT_REJECT)).thenReturn(2L);
        when(evalRepository.averageRating(docSetId)).thenReturn(4.2);

        var metrics = service.metrics(docSetId);

        assertThat(metrics.totalCaptured()).isEqualTo(50);
        assertThat(metrics.flagged()).isEqualTo(6);
        assertThat(metrics.sampled()).isEqualTo(4);
        assertThat(metrics.pending()).isEqualTo(30);
        assertThat(metrics.reviewed()).isEqualTo(18);
        assertThat(metrics.dismissed()).isEqualTo(2);
        assertThat(metrics.accepted()).isEqualTo(12);
        assertThat(metrics.reworded()).isEqualTo(4);
        assertThat(metrics.rejected()).isEqualTo(2);
        assertThat(metrics.averageRating()).isEqualTo(4.2);
    }

    private AnswerEval eval(UUID evalId, String question, String answer, List<String> flags, boolean sampled) {
        return new AnswerEval(evalId, docSetId, UUID.randomUUID(), UUID.randomUUID(),
                question, answer, "[]", AnswerEval.ORIGIN_CHAT, flags, 0.9, sampled, Instant.now());
    }
}
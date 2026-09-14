package com.healthrecon.rag.service.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.AnswerEvalResponse;
import com.healthrecon.rag.api.dto.EvalMetricsResponse;
import com.healthrecon.rag.api.dto.ExpectedSource;
import com.healthrecon.rag.api.dto.GoldenCaseResponse;
import com.healthrecon.rag.api.dto.ReviewRequest;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.domain.AnswerEval;
import com.healthrecon.rag.domain.GoldenCase;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.AnswerEvalRepository;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import com.healthrecon.rag.service.DocumentSetService;
import com.healthrecon.rag.service.GoldenCaseService;
import com.healthrecon.rag.service.semanticcache.SemanticCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AnswerEvalService {

    private static final TypeReference<List<SourceResponse>> SOURCES_TYPE = new TypeReference<>() {
    };
    private static final int MAX_PAGE_SIZE = 100;

    private final AnswerEvalRepository evalRepository;
    private final GoldenCaseService goldenCaseService;
    private final DocumentSetService documentSetService;
    private final SemanticCache semanticCache;
    private final ObjectMapper objectMapper;

    public AnswerEvalService(AnswerEvalRepository evalRepository,
                             GoldenCaseService goldenCaseService,
                             DocumentSetService documentSetService,
                             SemanticCache semanticCache,
                             ObjectMapper objectMapper) {
        this.evalRepository = evalRepository;
        this.goldenCaseService = goldenCaseService;
        this.documentSetService = documentSetService;
        this.semanticCache = semanticCache;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Page<AnswerEvalResponse> list(UUID docSetId, int page, int size,
                                         String status, Boolean sampled, Boolean flagged) {
        documentSetService.requireOwnedSet(docSetId);
        Specification<AnswerEval> spec = (root, query, cb) -> cb.equal(root.get("docSetId"), docSetId);
        if (status != null && !status.isBlank()) {
            validateStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("reviewStatus"), status.strip()));
        }
        if (Boolean.TRUE.equals(sampled)) {
            spec = spec.and((root, query, cb) -> cb.isTrue(root.get("sampled")));
        }
        if (Boolean.TRUE.equals(flagged)) {
            spec = spec.and((root, query, cb) -> cb.isNotNull(root.get("autoFlags")));
        }
        Pageable pageable = PageRequest.of(page, Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return evalRepository.findAll(spec, pageable).map(eval -> AnswerEvalResponse.from(eval, objectMapper));
    }

    @Transactional(readOnly = true)
    public AnswerEvalResponse get(UUID docSetId, UUID evalId) {
        return AnswerEvalResponse.from(loadOwned(docSetId, evalId), objectMapper);
    }

    @Transactional
    public AnswerEvalResponse review(UUID docSetId, UUID evalId, ReviewRequest request) {
        AnswerEval eval = loadOwned(docSetId, evalId);
        String verdict = request.verdict().strip();
        eval.review(verdict, request.rating(), request.comment(), request.correctedAnswer(), Instant.now());
        if (AnswerEval.VERDICT_REJECT.equals(verdict) || AnswerEval.VERDICT_REWORD.equals(verdict)) {
            semanticCache.invalidate(docSetId);
        }
        return AnswerEvalResponse.from(eval, objectMapper);
    }

    @Transactional
    public void dismiss(UUID docSetId, UUID evalId) {
        AnswerEval eval = loadOwned(docSetId, evalId);
        eval.dismiss(Instant.now());
    }

    /**
     * Turns a reviewed eval's corrected answer into a DRAFT golden case.
     */
    @Transactional
    public GoldenCaseResponse promote(UUID docSetId, UUID evalId) {
        AnswerEval eval = loadOwned(docSetId, evalId);
        if (eval.getCorrectedAnswer() == null || eval.getCorrectedAnswer().isBlank()) {
            throw new IllegalArgumentException("A corrected answer is required to promote to a golden case");
        }
        CurrentUser user = CurrentUserSupport.require();
        List<SourceResponse> sources = parseSources(eval.getSources());
        List<ExpectedSource> expectedSources = sources.stream()
                .map(source -> new ExpectedSource(source.filename(), source.docId(), source.section()))
                .toList();
        String expectedJson = expectedSources.isEmpty() ? "[]" : toJson(expectedSources);
        UUID sourceDocId = sources.isEmpty() ? null : sources.get(0).docId();
        GoldenCase goldenCase = goldenCaseService.saveGenerated(
                docSetId, user.id(), sourceDocId, eval.getQuestion().strip(),
                eval.getCorrectedAnswer().strip(), expectedJson);
        return GoldenCaseResponse.from(goldenCase, expectedSources);
    }

    @Transactional(readOnly = true)
    public EvalMetricsResponse metrics(UUID docSetId) {
        documentSetService.requireOwnedSet(docSetId);
        return new EvalMetricsResponse(
                evalRepository.countByDocSetId(docSetId),
                evalRepository.countByDocSetIdAndAutoFlagsIsNotNull(docSetId),
                evalRepository.countByDocSetIdAndSampledTrue(docSetId),
                evalRepository.countByDocSetIdAndReviewStatus(docSetId, AnswerEval.REVIEW_PENDING),
                evalRepository.countByDocSetIdAndReviewStatus(docSetId, AnswerEval.REVIEW_REVIEWED),
                evalRepository.countByDocSetIdAndReviewStatus(docSetId, AnswerEval.REVIEW_DISMISSED),
                evalRepository.countByDocSetIdAndVerdict(docSetId, AnswerEval.VERDICT_ACCEPT),
                evalRepository.countByDocSetIdAndVerdict(docSetId, AnswerEval.VERDICT_REWORD),
                evalRepository.countByDocSetIdAndVerdict(docSetId, AnswerEval.VERDICT_REJECT),
                evalRepository.averageRating(docSetId));
    }

    private AnswerEval loadOwned(UUID docSetId, UUID evalId) {
        documentSetService.requireOwnedSet(docSetId);
        return evalRepository.findByIdAndDocSetId(evalId, docSetId)
                .orElseThrow(() -> new NotFoundException("Evaluation not found"));
    }

    private List<SourceResponse> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourcesJson, SOURCES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<ExpectedSource> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize expected sources", e);
        }
    }

    private static void validateStatus(String status) {
        String normalized = status.strip();
        if (!List.of(AnswerEval.REVIEW_PENDING, AnswerEval.REVIEW_REVIEWED, AnswerEval.REVIEW_DISMISSED)
                .contains(normalized)) {
            throw new IllegalArgumentException("status must be PENDING, REVIEWED or DISMISSED");
        }
    }
}
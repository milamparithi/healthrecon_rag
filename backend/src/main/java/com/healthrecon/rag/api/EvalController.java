package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.AnswerEvalResponse;
import com.healthrecon.rag.api.dto.EvalMetricsResponse;
import com.healthrecon.rag.api.dto.GoldenCaseResponse;
import com.healthrecon.rag.api.dto.PageResponse;
import com.healthrecon.rag.api.dto.ReviewRequest;
import com.healthrecon.rag.service.eval.AnswerEvalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/documentsets/{docSetId}/evals")
public class EvalController {

    private final AnswerEvalService answerEvalService;

    public EvalController(AnswerEvalService answerEvalService) {
        this.answerEvalService = answerEvalService;
    }

    @GetMapping
    public PageResponse<AnswerEvalResponse> list(
            @PathVariable UUID docSetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean sampled,
            @RequestParam(required = false) Boolean flagged) {
        return PageResponse.from(answerEvalService.list(docSetId, page, size, status, sampled, flagged));
    }

    @GetMapping("/{evalId}")
    public AnswerEvalResponse get(@PathVariable UUID docSetId, @PathVariable UUID evalId) {
        return answerEvalService.get(docSetId, evalId);
    }

    @PatchMapping("/{evalId}")
    public AnswerEvalResponse review(@PathVariable UUID docSetId, @PathVariable UUID evalId,
                                     @Valid @RequestBody ReviewRequest request) {
        return answerEvalService.review(docSetId, evalId, request);
    }

    @PostMapping("/{evalId}/dismiss")
    public ResponseEntity<Void> dismiss(@PathVariable UUID docSetId, @PathVariable UUID evalId) {
        answerEvalService.dismiss(docSetId, evalId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{evalId}/promote")
    public GoldenCaseResponse promote(@PathVariable UUID docSetId, @PathVariable UUID evalId) {
        return answerEvalService.promote(docSetId, evalId);
    }

    @GetMapping("/metrics")
    public EvalMetricsResponse metrics(@PathVariable UUID docSetId) {
        return answerEvalService.metrics(docSetId);
    }
}
package com.healthrecon.rag.api;

import com.healthrecon.rag.api.dto.GoldenCaseRequest;
import com.healthrecon.rag.api.dto.GoldenCaseResponse;
import com.healthrecon.rag.api.dto.GoldenEvalReport;
import com.healthrecon.rag.service.GoldenCaseService;
import com.healthrecon.rag.service.GoldenEvaluationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documentsets/{docSetId}/golden")
public class GoldenCaseController {

    private final GoldenCaseService goldenCaseService;
    private final GoldenEvaluationService goldenEvaluationService;

    public GoldenCaseController(GoldenCaseService goldenCaseService,
                                GoldenEvaluationService goldenEvaluationService) {
        this.goldenCaseService = goldenCaseService;
        this.goldenEvaluationService = goldenEvaluationService;
    }

    @GetMapping
    public List<GoldenCaseResponse> list(@PathVariable UUID docSetId) {
        return goldenCaseService.list(docSetId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoldenCaseResponse create(@PathVariable UUID docSetId,
                                     @Valid @RequestBody GoldenCaseRequest request) {
        return goldenCaseService.create(docSetId, request);
    }

    @PatchMapping("/{caseId}")
    public GoldenCaseResponse update(@PathVariable UUID docSetId,
                                     @PathVariable UUID caseId,
                                     @Valid @RequestBody GoldenCaseRequest request) {
        return goldenCaseService.update(docSetId, caseId, request);
    }

    @DeleteMapping("/{caseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID docSetId, @PathVariable UUID caseId) {
        goldenCaseService.delete(docSetId, caseId);
    }

    @PostMapping("/run")
    public GoldenEvalReport run(@PathVariable UUID docSetId) {
        return goldenEvaluationService.run(docSetId);
    }
}
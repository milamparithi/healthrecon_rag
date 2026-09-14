package com.healthrecon.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.ExpectedSource;
import com.healthrecon.rag.api.dto.GoldenCaseRequest;
import com.healthrecon.rag.api.dto.GoldenCaseResponse;
import com.healthrecon.rag.domain.GoldenCase;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.GoldenCaseRepository;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GoldenCaseService {

    private static final TypeReference<List<ExpectedSource>> SOURCES_TYPE = new TypeReference<>() {
    };

    private final DocumentSetService documentSetService;
    private final GoldenCaseRepository goldenCaseRepository;
    private final ObjectMapper objectMapper;

    public GoldenCaseService(DocumentSetService documentSetService,
                             GoldenCaseRepository goldenCaseRepository,
                             ObjectMapper objectMapper) {
        this.documentSetService = documentSetService;
        this.goldenCaseRepository = goldenCaseRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<GoldenCaseResponse> list(UUID docSetId) {
        documentSetService.requireOwnedSet(docSetId);
        return goldenCaseRepository.findAllByDocSetIdOrderByCreatedAtDesc(docSetId).stream()
                .map(goldenCase -> GoldenCaseResponse.from(goldenCase, parseSources(goldenCase.getExpectedSources())))
                .toList();
    }

    @Transactional
    public GoldenCaseResponse create(UUID docSetId, GoldenCaseRequest request) {
        documentSetService.requireOwnedSet(docSetId);
        CurrentUser user = CurrentUserSupport.require();
        Instant now = Instant.now();
        String sourcesJson = toJson(sourcesOrDefault(request.expectedSources()));
        GoldenCase goldenCase = new GoldenCase(
                UUID.randomUUID(), docSetId, user.id(), null,
                request.question().strip(), request.answer().strip(),
                sourcesJson, resolveStatus(request.status()), now, now);
        return GoldenCaseResponse.from(goldenCaseRepository.save(goldenCase),
                parseSources(goldenCase.getExpectedSources()));
    }

    @Transactional
    public GoldenCaseResponse update(UUID docSetId, UUID caseId, GoldenCaseRequest request) {
        GoldenCase goldenCase = loadOwned(docSetId, caseId);
        Instant now = Instant.now();
        String sourcesJson = request.expectedSources() == null
                ? goldenCase.getExpectedSources()
                : toJson(request.expectedSources());
        goldenCase.update(request.question().strip(), request.answer().strip(),
                sourcesJson, resolveStatus(request.status()), now);
        return GoldenCaseResponse.from(goldenCase, parseSources(goldenCase.getExpectedSources()));
    }

    @Transactional
    public void delete(UUID docSetId, UUID caseId) {
        requireOwned(docSetId, caseId);
        goldenCaseRepository.deleteById(caseId);
    }

    /**
     * Persists a newly generated (or human-authored) case. The expected-sources JSON
     * must already be serialized.
     */
    public GoldenCase saveGenerated(UUID docSetId, UUID ownerId, UUID sourceDocId,
                                    String question, String referenceAnswer, String expectedSourcesJson) {
        Instant now = Instant.now();
        GoldenCase goldenCase = new GoldenCase(
                UUID.randomUUID(), docSetId, ownerId, sourceDocId,
                question, referenceAnswer, expectedSourcesJson, GoldenCase.STATUS_DRAFT, now, now);
        return goldenCaseRepository.save(goldenCase);
    }

    @Transactional(readOnly = true)
    public void requireOwned(UUID docSetId, UUID caseId) {
        documentSetService.requireOwnedSet(docSetId);
        CurrentUser user = CurrentUserSupport.require();
        goldenCaseRepository.findByIdAndOwnerIdAndDocSetId(caseId, user.id(), docSetId)
                .orElseThrow(() -> new NotFoundException("Golden case not found"));
    }

    @Transactional(readOnly = true)
    public GoldenCase loadOwned(UUID docSetId, UUID caseId) {
        documentSetService.requireOwnedSet(docSetId);
        CurrentUser user = CurrentUserSupport.require();
        return goldenCaseRepository.findByIdAndOwnerIdAndDocSetId(caseId, user.id(), docSetId)
                .orElseThrow(() -> new NotFoundException("Golden case not found"));
    }

    List<ExpectedSource> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourcesJson, SOURCES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    String toJson(List<ExpectedSource> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize expected sources", e);
        }
    }

    private static List<ExpectedSource> sourcesOrDefault(List<ExpectedSource> sources) {
        return sources == null ? List.of() : sources;
    }

    private static String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return GoldenCase.STATUS_DRAFT;
        }
        String normalized = status.strip();
        if (GoldenCase.STATUS_DRAFT.equals(normalized) || GoldenCase.STATUS_GOLDEN.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("status must be DRAFT or GOLDEN");
    }
}
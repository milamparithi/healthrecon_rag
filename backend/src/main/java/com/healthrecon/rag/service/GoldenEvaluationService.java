package com.healthrecon.rag.service;

import com.healthrecon.rag.api.dto.CaseEvalResult;
import com.healthrecon.rag.api.dto.ExpectedSource;
import com.healthrecon.rag.api.dto.GoldenEvalReport;
import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.domain.GoldenCase;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.GoldenCaseRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs deterministic retrieval-only evaluation over GOLDEN cases: embeds each
 * question, retrieves the top-k chunks and scores them against the expected
 * source filenames. No LLM is invoked, so the run is free and repeatable.
 * Cases whose expected sources no longer exist in the set (document drift) are
 * excluded from the aggregates and listed as warnings.
 */
@Service
public class GoldenEvaluationService {

    private final DocumentSetService documentSetService;
    private final GoldenCaseRepository goldenCaseRepository;
    private final GoldenCaseService goldenCaseService;
    private final DocumentRepository documentRepository;
    private final EmbeddingModel embeddingModel;
    private final VectorIndexer vectorIndexer;
    private final RagProperties ragProperties;

    public GoldenEvaluationService(DocumentSetService documentSetService,
                                   GoldenCaseRepository goldenCaseRepository,
                                   GoldenCaseService goldenCaseService,
                                   DocumentRepository documentRepository,
                                   EmbeddingModel embeddingModel,
                                   VectorIndexer vectorIndexer,
                                   RagProperties ragProperties) {
        this.documentSetService = documentSetService;
        this.goldenCaseRepository = goldenCaseRepository;
        this.goldenCaseService = goldenCaseService;
        this.documentRepository = documentRepository;
        this.embeddingModel = embeddingModel;
        this.vectorIndexer = vectorIndexer;
        this.ragProperties = ragProperties;
    }

    public GoldenEvalReport run(UUID docSetId) {
        documentSetService.requireOwnedSet(docSetId);

        List<GoldenCase> golden = goldenCaseRepository.findAllByDocSetIdOrderByCreatedAtDesc(docSetId).stream()
                .filter(goldenCase -> GoldenCase.STATUS_GOLDEN.equals(goldenCase.getStatus()))
                .toList();
        Set<String> knownFilenames = Set.copyOf(documentRepository.findFilenamesByDocSetId(docSetId));
        int topK = Math.max(1, ragProperties.topK());

        List<String> warnings = new ArrayList<>();
        List<CaseEvalResult> results = new ArrayList<>();
        int evaluated = 0;
        double recallSum = 0;
        double mrrSum = 0;
        int hitCount = 0;

        for (GoldenCase goldenCase : golden) {
            List<String> expected = goldenCaseService.parseSources(goldenCase.getExpectedSources()).stream()
                    .map(ExpectedSource::filename)
                    .filter(filename -> filename != null && !filename.isBlank())
                    .toList();
            if (expected.isEmpty()) {
                warnings.add("Case \"%s\" has no expected sources; skipped".formatted(shorten(goldenCase.getQuestion())));
                continue;
            }
            List<String> missing = expected.stream().filter(filename -> !knownFilenames.contains(filename)).toList();
            if (!missing.isEmpty()) {
                warnings.add("Case \"%s\" references missing documents: %s; skipped"
                        .formatted(shorten(goldenCase.getQuestion()), String.join(", ", missing)));
                continue;
            }

            Embedding query = embeddingModel.embed(goldenCase.getQuestion()).content();
            List<String> retrieved = vectorIndexer.search(docSetId, goldenCase.getQuestion(), query, topK).stream()
                    .map(ChunkSearchHit::filename)
                    .filter(filename -> filename != null)
                    .toList();

            Set<String> retrievedSet = new LinkedHashSet<>(retrieved);
            long matched = expected.stream().filter(retrievedSet::contains).count();
            boolean hit = matched > 0;
            double recall = (double) matched / expected.size();
            int rank = 0;
            for (int i = 0; i < retrieved.size(); i++) {
                if (expected.contains(retrieved.get(i))) {
                    rank = i + 1;
                    break;
                }
            }

            results.add(new CaseEvalResult(goldenCase.getQuestion(), expected, retrieved, hit, rank, recall));
            evaluated++;
            recallSum += recall;
            if (hit) {
                hitCount++;
                mrrSum += 1.0 / rank;
            }
        }

        return new GoldenEvalReport(
                evaluated,
                evaluated == 0 ? 0.0 : recallSum / evaluated,
                evaluated == 0 ? 0.0 : mrrSum / evaluated,
                evaluated == 0 ? 0.0 : (double) hitCount / evaluated,
                results,
                warnings);
    }

    private static String shorten(String value) {
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }
}
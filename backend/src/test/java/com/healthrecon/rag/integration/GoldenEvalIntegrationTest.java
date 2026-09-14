package com.healthrecon.rag.integration;

import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.service.GoldenCaseGenerator;
import com.healthrecon.rag.service.IndexingService;
import com.healthrecon.rag.service.IngestionJob;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoldenEvalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IngestionJob ingestionJob;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private GoldenCaseGenerator goldenCaseGenerator;

    @Autowired
    private DocumentRepository documentRepository;

    @BeforeEach
    void stubModels() {
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(unitVector())));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<dev.langchain4j.data.segment.TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> new Embedding(unitVector())).toList());
        });
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage(
                        "[{\"question\":\"What is the first step for headaches?\","
                                + "\"answer\":\"Take paracetamol 500 mg.\"}]"))
                .build());
    }

    @Test
    void goldenDatasetLifecycleWithEvaluationAndIsolation() throws Exception {
        String tokenA = registerAndGetToken("eval-a@example.com", "password123", "Eval A");
        String tokenB = registerAndGetToken("eval-b@example.com", "password123", "Eval B");
        String authA = bearer(tokenA);
        String authB = bearer(tokenB);

        MvcResult created = mvc.perform(post("/api/documentsets")
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Headache Guidelines", "description", "golden eval"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docSetId = UUID.fromString(parseBody(created.getResponse().getContentAsString()).get("id").asText());

        String markdown = """
                # Acute Headache Management

                Step 1: take paracetamol 500 mg.

                ## Red Flags

                Sudden severe onset requires urgent imaging.
                """;
        mvc.perform(multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "headache.md", "text/markdown", markdown.getBytes()))
                        .header("Authorization", authA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("UPLOADED"));

        ingestionJob.processPending();

        mvc.perform(get("/api/documentsets/{id}", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        MvcResult docs = mvc.perform(get("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andReturn();
        UUID docId = UUID.fromString(parseBody(docs.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText());

        StoredDocument doc = documentRepository.findById(docId).orElseThrow();
        indexingService.indexDocument(doc);
        goldenCaseGenerator.generate(doc);

        // draft case appears with expected source
        mvc.perform(get("/api/documentsets/{id}/golden", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].referenceAnswer").value("Take paracetamol 500 mg."))
                .andExpect(jsonPath("$[0].expectedSources[0].filename").value("headache.md"));

        UUID caseId = UUID.fromString(parseBody(mvc.perform(get("/api/documentsets/{id}/golden", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText());

        // review: promote to GOLDEN
        mvc.perform(patch("/api/documentsets/{docSetId}/golden/{caseId}", docSetId, caseId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of(
                                "question", "What is the first step for headaches?",
                                "answer", "Take paracetamol 500 mg.",
                                "status", "GOLDEN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GOLDEN"));

        // run evaluation: retrieval must hit the expected source
        mvc.perform(post("/api/documentsets/{id}/golden/run", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.casesEvaluated").value(1))
                .andExpect(jsonPath("$.recallAtK").value(1.0))
                .andExpect(jsonPath("$.mrr").value(1.0))
                .andExpect(jsonPath("$.hitRate").value(1.0))
                .andExpect(jsonPath("$.cases[0].hit").value(true))
                .andExpect(jsonPath("$.cases[0].rank").value(1))
                .andExpect(jsonPath("$.cases[0].expected[0]").value("headache.md"))
                .andExpect(jsonPath("$.cases[0].retrieved.length()").value(greaterThanOrEqualTo(1)));

        // another user can neither read golden cases nor run evaluation
        mvc.perform(get("/api/documentsets/{id}/golden", docSetId).header("Authorization", authB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/documentsets/{id}/golden/run", docSetId).header("Authorization", authB))
                .andExpect(status().isNotFound());

        // deleting the set cascades: golden cases disappear with it
        mvc.perform(delete("/api/documentsets/{id}", docSetId).header("Authorization", authA))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/documentsets/{id}/golden", docSetId).header("Authorization", authA))
                .andExpect(status().isNotFound());
    }

    private static float[] unitVector() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
package com.healthrecon.rag.integration;

import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.service.IndexingService;
import com.healthrecon.rag.service.IngestionJob;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IngestionJob ingestionJob;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private DocumentRepository documentRepository;

    @BeforeEach
    void stubModels() {
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(unitVector())));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<dev.langchain4j.data.segment.TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> new Embedding(unitVector())).toList());
        });
    }

    @Test
    void chatCaptureReviewPromoteAndCacheEviction() throws Exception {
        String authA = bearer(registerAndGetToken("eval-owner@example.com", "password123", "Eval Owner"));
        String authB = bearer(registerAndGetToken("eval-other@example.com", "password123", "Eval Other"));
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("Take paracetamol 500 mg as the first step [1].")).build());

        UUID docSetId = readySet(authA, "Eval Guidelines", "clinical guide");
        MvcResult conv = mvc.perform(post("/api/documentsets/{id}/conversations", docSetId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID conversationId = UUID.fromString(parseBody(conv.getResponse().getContentAsString()).get("id").asText());

        String message = "What should the patient take?";
        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("message", message))))
                .andExpect(status().isOk());

        // every fresh answer is captured for review
        MvcResult listOne = mvc.perform(get("/api/documentsets/{id}/evals", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andReturn();
        UUID evalId = UUID.fromString(parseBody(listOne.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText());

        // a cache hit produces no new eval row
        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("message", message))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documentsets/{id}/evals", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // reject the answer: evicts the cache and accepts a corrected answer
        mvc.perform(patch("/api/documentsets/{id}/evals/{evalId}", docSetId, evalId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of(
                                "verdict", "REJECT",
                                "rating", 1,
                                "comment", "hallucinated strength",
                                "correctedAnswer", "Take 500 mg paracetamol."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REVIEWED"))
                .andExpect(jsonPath("$.verdict").value("REJECT"))
                .andExpect(jsonPath("$.correctedAnswer").value("Take 500 mg paracetamol."));

        // promote the corrected answer into a DRAFT golden case
        mvc.perform(post("/api/documentsets/{id}/evals/{evalId}/promote", docSetId, evalId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.referenceAnswer").value("Take 500 mg paracetamol."));
        mvc.perform(get("/api/documentsets/{id}/golden", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // the rejected answer was evicted from the cache: asking again stays fresh
        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("message", message))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/documentsets/{id}/evals", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // foreign users cannot see or promote the evals
        mvc.perform(get("/api/documentsets/{id}/evals", docSetId).header("Authorization", authB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/documentsets/{id}/evals/{evalId}/promote", docSetId, evalId)
                        .header("Authorization", authB))
                .andExpect(status().isNotFound());

        // metrics reflect what was captured and reviewed
        mvc.perform(get("/api/documentsets/{id}/evals/metrics", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCaptured").value(2))
                .andExpect(jsonPath("$.reviewed").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.accepted").value(0));
    }

    @Test
    void refusedAnswerIsCapturedAndFlagged() throws Exception {
        String authA = bearer(registerAndGetToken("eval-refusal@example.com", "password123", "Eval Refusal"));
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("This medicine will definitely cure you.")).build());

        UUID docSetId = readySet(authA, "Refusal Guidelines", "clinical guide");
        MvcResult conv = mvc.perform(post("/api/documentsets/{id}/conversations", docSetId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID conversationId = UUID.fromString(parseBody(conv.getResponse().getContentAsString()).get("id").asText());

        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("message", "Is it guaranteed to work?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(0));

        MvcResult flagged = mvc.perform(get("/api/documentsets/{id}/evals", docSetId)
                        .header("Authorization", authA)
                        .param("flagged", "true")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode row = parseBody(flagged.getResponse().getContentAsString()).get("content").get(0);
        assertThat(row.get("autoFlags").get(0).asText()).isEqualTo("GUARDRAIL_REFUSAL");

        UUID evalId = UUID.fromString(row.get("id").asText());
        mvc.perform(post("/api/documentsets/{id}/evals/{evalId}/dismiss", docSetId, evalId)
                        .header("Authorization", authA))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/documentsets/{id}/evals", docSetId)
                        .header("Authorization", authA)
                        .param("status", "DISMISSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private UUID readySet(String auth, String name, String description) throws Exception {
        MvcResult created = mvc.perform(post("/api/documentsets")
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("name", name, "description", description))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docSetId = UUID.fromString(parseBody(created.getResponse().getContentAsString()).get("id").asText());

        String markdown = """
                # Guidelines

                Step 1: take paracetamol 500 mg.

                ## Red Flags

                Sudden severe onset requires urgent imaging.
                """;
        mvc.perform(multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "guide.md", "text/markdown", markdown.getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isCreated());

        ingestionJob.processPending();
        mvc.perform(get("/api/documentsets/{id}", docSetId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        MvcResult docs = mvc.perform(get("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        UUID docId = UUID.fromString(parseBody(docs.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText());
        StoredDocument doc = documentRepository.findById(docId).orElseThrow();
        indexingService.indexDocument(doc);
        return docSetId;
    }

    private static float[] unitVector() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
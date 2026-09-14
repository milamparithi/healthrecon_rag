package com.healthrecon.rag.integration;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the semantic cache against real Qdrant: a semantically identical
 * question is served from the cache (the stub LLM is invoked exactly once) and
 * a document change (re-index invalidates) forces a fresh LLM call.
 */
class SemanticCacheIntegrationTest extends BaseIntegrationTest {

    private static final String Q1 = "What should the patient take for the headache?";
    private static final String Q2 = "Which red flag requires urgent imaging?";
    private static final String ANSWER = "Take paracetamol 500 mg as the first step [1].";

    @Autowired
    private com.healthrecon.rag.service.IngestionJob ingestionJob;

    @Autowired
    private com.healthrecon.rag.service.IndexingService indexingService;

    @Autowired
    private com.healthrecon.rag.repository.DocumentRepository documentRepository;

    @BeforeEach
    void stubModels() {
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> Response.from(embeddingFor(invocation.getArgument(0))));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<dev.langchain4j.data.segment.TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> embeddingFor(s.text().trim())).toList());
        });
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage(ANSWER)).build());
    }

    @Test
    void cachesRepeatedQuestionsAndInvalidatesOnDocumentChange() throws Exception {
        String token = registerAndGetToken("cache-dr@example.com", "password123", "Cache Dr");
        String auth = bearer(token);

        MvcResult created = mvc.perform(post("/api/documentsets")
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(java.util.Map.of("name", "Cache Set", "description", "cache test"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docSetId = UUID.fromString(parseBody(created.getResponse().getContentAsString()).get("id").asText());

        upload(auth, docSetId, "headache.md",
                "# Acute Headache\n\nStep 1: take paracetamol 500 mg.\n\n## Red Flags\n\nSudden severe onset requires urgent imaging.");
        upload(auth, docSetId, "migraine.md",
                "# Migraine\n\nSumatriptan at onset of aura.");
        ingestionJob.processPending();

        JsonNode docs = parseBody(mvc.perform(get("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID migraineDocId = null;
        for (JsonNode node : docs.get("content")) {
            if ("migraine.md".equals(node.get("filename").asText())) {
                migraineDocId = UUID.fromString(node.get("id").asText());
            }
        }
        assertThat(migraineDocId).isNotNull();

        for (JsonNode node : docs.get("content")) {
            indexingService.indexDocument(documentRepository.findById(
                    UUID.fromString(node.get("id").asText())).orElseThrow());
        }

        MvcResult conv = mvc.perform(post("/api/documentsets/{id}/conversations", docSetId)
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID conversationId = UUID.fromString(parseBody(conv.getResponse().getContentAsString()).get("id").asText());

        chat(auth, docSetId, conversationId, Q1).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(ANSWER));
        verify(chatModel, times(1)).chat(anyList());

        chat(auth, docSetId, conversationId, Q1).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(ANSWER));
        verify(chatModel, times(1)).chat(anyList());

        chat(auth, docSetId, conversationId, Q2).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(ANSWER));
        verify(chatModel, times(2)).chat(anyList());

        mvc.perform(delete("/api/documentsets/{id}/documents/{docId}", docSetId, migraineDocId)
                        .header("Authorization", auth))
                .andExpect(status().isNoContent());

        chat(auth, docSetId, conversationId, Q1).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(ANSWER));
        verify(chatModel, times(3)).chat(anyList());

        mvc.perform(delete("/api/documentsets/{id}", docSetId).header("Authorization", auth))
                .andExpect(status().isNoContent());
    }

    private void upload(String auth, UUID docSetId, String filename, String markdown) throws Exception {
        mvc.perform(multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", filename, "text/markdown", markdown.getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions chat(String auth, UUID docSetId,
                                                                    UUID conversationId, String message)
            throws Exception {
        return mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                .header("Authorization", auth)
                .contentType(appJson())
                .content(json(java.util.Map.of("message", message))));
    }

    private static Embedding embeddingFor(String text) {
        float[] vector = new float[768];
        int hash = text.hashCode();
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) Math.sin(hash * (i + 1) * 0.0001);
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) norm;
        }
        return new Embedding(vector);
    }
}
package com.healthrecon.rag.integration;

import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatIntegrationTest extends BaseIntegrationTest {

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
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("Paracetamol is recommended for headaches.")).build());
    }

    @Test
    void fullChatLifecycleWithIndexingAndIsolation() throws Exception {
        String tokenA = registerAndGetToken("dr-smith@example.com", "password123", "Dr Smith");
        String tokenB = registerAndGetToken("dr-jones@example.com", "password123", "Dr Jones");
        String authA = bearer(tokenA);
        String authB = bearer(tokenB);

        MvcResult created = mvc.perform(post("/api/documentsets")
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Headache Guidelines", "description", "clinical guide"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID docSetId = UUID.fromString(parseBody(created.getResponse().getContentAsString()).get("id").asText());

        String markdown = """
                # Acute Headache Management

                Step 1: take paracetamol 500 mg.

                ## Red Flags

                Sudden severe onset requires urgent imaging.

                ## Migraine

                Sumatriptan at onset of aura.
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

        assertThat(documentRepository.findById(docId).orElseThrow().getIndexStatus()).isEqualTo(IndexStatus.INDEXED);

        // conversations start empty
        mvc.perform(get("/api/documentsets/{id}/conversations", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        MvcResult convCreated = mvc.perform(post("/api/documentsets/{id}/conversations", docSetId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("New conversation"))
                .andReturn();
        UUID conversationId = UUID.fromString(
                parseBody(convCreated.getResponse().getContentAsString()).get("id").asText());

        // chat with retrieved context
        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("message", "What should the patient take for the headache?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Paracetamol is recommended for headaches."))
                .andExpect(jsonPath("$.sources[0].filename").value("headache.md"))
                .andExpect(jsonPath("$.title", org.hamcrest.Matchers.containsString("What should the patient take")));

        // conversation is renamed based on the first message
        mvc.perform(get("/api/documentsets/{id}/conversations", docSetId).header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title", org.hamcrest.Matchers.containsString("What should the patient take")));

        // messages persisted incl. sources
        mvc.perform(get("/api/documentsets/{setId}/conversations/{convId}/messages", docSetId, conversationId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].sources.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // user B cannot see or use A's conversation
        mvc.perform(get("/api/documentsets/{id}/conversations", docSetId).header("Authorization", authB))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", authB)
                        .contentType(appJson())
                        .content(json(Map.of("message", "stolen"))))
                .andExpect(status().isNotFound());

        // delete conversation
        mvc.perform(delete("/api/documentsets/{setId}/conversations/{convId}", docSetId, conversationId)
                        .header("Authorization", authA))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/documentsets/{setId}/conversations/{convId}/messages", docSetId, conversationId)
                        .header("Authorization", authA))
                .andExpect(status().isNotFound());

        // deleting the set removes the conversations and vectors
        mvc.perform(delete("/api/documentsets/{id}", docSetId).header("Authorization", authA))
                .andExpect(status().isNoContent());
    }

    private static float[] unitVector() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
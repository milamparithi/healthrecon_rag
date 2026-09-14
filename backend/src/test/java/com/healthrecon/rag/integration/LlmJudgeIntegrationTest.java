package com.healthrecon.rag.integration;

import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.service.IndexingService;
import com.healthrecon.rag.service.IngestionJob;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.healthrecon.rag.service.guardrails.ChatGuardrailService.REFUSAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.ingestion.poll-ms=3600000",
        "app.ingestion.initial-delay-ms=3600000",
        "app.indexing.poll-ms=3600000",
        "app.indexing.initial-delay-ms=3600000",
        "app.golden.enabled=false",
        "app.golden.poll-ms=3600000",
        "app.golden.initial-delay-ms=3600000",
        "llm.embedding-dimension=768",
        "app.rag.guardrails.llm-check-enabled=true"
})
class LlmJudgeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IngestionJob ingestionJob;

    @Autowired
    private IndexingService indexingService;

    @Autowired
    private DocumentRepository documentRepository;

    private String auth;
    private UUID docSetId;
    private UUID conversationId;

    @BeforeEach
    void setUpFixture() throws Exception {
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(unitVector())));
        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<dev.langchain4j.data.segment.TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(s -> new Embedding(unitVector())).toList());
        });

        String token = registerAndGetToken("llmjudge-" + UUID.randomUUID() + "@example.com", "password123",
                "Llm Judge Doc");
        auth = bearer(token);

        MvcResult setCreated = mvc.perform(post("/api/documentsets")
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Headache Guidelines", "description", "clinical guide"))))
                .andExpect(status().isCreated())
                .andReturn();
        docSetId = UUID.fromString(parseBody(setCreated.getResponse().getContentAsString()).get("id").asText());

        String markdown = """
                # Acute Headache Management

                Step 1: take paracetamol 500 mg.
                """;
        mvc.perform(multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "headache.md", "text/markdown", markdown.getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("UPLOADED"));

        ingestionJob.processPending();

        MvcResult docs = mvc.perform(get("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        UUID docId = UUID.fromString(parseBody(docs.getResponse().getContentAsString())
                .get("content").get(0).get("id").asText());
        StoredDocument doc = documentRepository.findById(docId).orElseThrow();
        indexingService.indexDocument(doc);
        assertThat(doc.getIndexStatus()).isEqualTo(IndexStatus.INDEXED);

        MvcResult convCreated = mvc.perform(post("/api/documentsets/{id}/conversations", docSetId)
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        conversationId = UUID.fromString(
                parseBody(convCreated.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void judgeInconsistentAnswerIsRefused() throws Exception {
        stubJudge("{\"consistent\": false, \"reason\": \"Answer goes beyond the context\"}");

        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("message", "What should the patient take for the headache?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(REFUSAL))
                .andExpect(jsonPath("$.sources.length()").value(0));

        // One call for the answer, one for the judge.
        verify(chatModel, times(2)).chat(anyList());
    }

    @Test
    void judgeConsistentAnswerPasses() throws Exception {
        stubJudge("{\"consistent\": true, \"reason\": \"Answer matches the context\"}");

        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("message", "What should the patient take for the headache?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Take paracetamol 500 mg as the first step [1]."))
                .andExpect(jsonPath("$.sources[0].filename").value("headache.md"));

        verify(chatModel, times(2)).chat(anyList());
    }

    private void stubJudge(String judgeJson) {
        when(chatModel.chat(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            boolean judgeCall = messages.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(m -> ((SystemMessage) m).text())
                    .anyMatch(text -> text != null && text.contains("factual consistency checker"));
            if (judgeCall) {
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new AiMessage(judgeJson)).build();
            }
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(new AiMessage("Take paracetamol 500 mg as the first step [1].")).build();
        });
    }

    private static float[] unitVector() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
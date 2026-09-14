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
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/**
 * Proves the optional query rewritern end-to-end against real Postgres + Qdrant. All dense
 * embeddings are identical unit vectors so the cosine leg cannot discriminate; only the sparse
 * BM25 leg (built client-side from the rewritten query text) can steer retrieval. The shared
 * {@code chatModel} stub tells the rewrite call apart from the answer call by the prompt's
 * SystemMessage text.
 */
@SpringBootTest(properties = {
        "app.ingestion.poll-ms=3600000",
        "app.ingestion.initial-delay-ms=3600000",
        "app.indexing.poll-ms=3600000",
        "app.indexing.initial-delay-ms=3600000",
        "app.golden.enabled=false",
        "app.golden.poll-ms=3600000",
        "app.golden.initial-delay-ms=3600000",
        "llm.embedding-dimension=768",
        "app.rag.query-rewrite.enabled=true"
})
class QueryRewriteIntegrationTest extends BaseIntegrationTest {

    private static final String REWRITE_MARKER = "rewrite the user's question";
    private static final String STEERING_ANSWER =
            "For severe pain that persists, escalate to an intramuscular injection of tramadol [1].";
    private static final String HEADACHE_ANSWER = "Take paracetamol 500 mg for the headache [1].";

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

        String token = registerAndGetToken("rewrite-" + UUID.randomUUID() + "@example.com", "password123",
                "Rewrite Doc");
        auth = bearer(token);

        MvcResult setCreated = mvc.perform(post("/api/documentsets")
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Rewrite Docs", "description", "clinical guide"))))
                .andExpect(status().isCreated())
                .andReturn();
        docSetId = UUID.fromString(parseBody(setCreated.getResponse().getContentAsString()).get("id").asText());

        String headache = """
                # Acute Headache Management

                Step 1: take paracetamol 500 mg for the headache.
                """;
        String escalation = """
                # Severe Case Escalation

                For severe pain that persists, escalate to an intramuscular injection of tramadol.
                """;
        mvc.perform(multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "headache.md", "text/markdown", headache.getBytes()))
                        .file(new MockMultipartFile("files", "escalation.md", "text/markdown", escalation.getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("UPLOADED"));

        ingestionJob.processPending();

        MvcResult docs = mvc.perform(get("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        for (var node : parseBody(docs.getResponse().getContentAsString()).get("content")) {
            StoredDocument doc = documentRepository.findById(UUID.fromString(node.get("id").asText())).orElseThrow();
            indexingService.indexDocument(doc);
            assertThat(doc.getIndexStatus()).isEqualTo(IndexStatus.INDEXED);
        }

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
    void rewrittenQueryDrivesRetrievalButPromptKeepsOriginalQuestion() throws Exception {
        stubChat(() -> "tramadol for severe pain escalation", STEERING_ANSWER);

        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("message", "what comes next?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(STEERING_ANSWER))
                .andExpect(jsonPath("$.sources[0].filename").value("escalation.md"));

        // One call to rewrite the query, one to answer.
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatModel, times(2)).chat(captor.capture());
        List<ChatMessage> answerPrompt = captor.getAllValues().stream()
                .filter(call -> !isRewritePrompt(call))
                .findFirst()
                .orElseThrow();
        assertThat(((UserMessage) answerPrompt.get(answerPrompt.size() - 1)).singleText()).isEqualTo("what comes next?");
    }

    @Test
    void failedRewriteFallsBackToOriginalQuery() throws Exception {
        stubChat(() -> {
            throw new RuntimeException("LLM down");
        }, HEADACHE_ANSWER);

        mvc.perform(post("/api/documentsets/{setId}/conversations/{convId}/chat", docSetId, conversationId)
                        .header("Authorization", auth)
                        .contentType(appJson())
                        .content(json(Map.of("message", "what should I take for the headache?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(HEADACHE_ANSWER))
                .andExpect(jsonPath("$.sources[0].filename").value("headache.md"));

        // The failed rewrite call still counts once; retrieval fell back to the raw question.
        verify(chatModel, times(2)).chat(anyList());
    }

    private boolean isRewritePrompt(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).text())
                .anyMatch(text -> text != null && text.toLowerCase().contains(REWRITE_MARKER));
    }

    private void stubChat(RewriteCall rewriteCall, String answer) {
        when(chatModel.chat(anyList())).thenAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            if (isRewritePrompt(messages)) {
                return ChatResponse.builder().aiMessage(new AiMessage(rewriteCall.call())).build();
            }
            return ChatResponse.builder().aiMessage(new AiMessage(answer)).build();
        });
    }

    @FunctionalInterface
    private interface RewriteCall {
        String call() throws Exception;
    }

    private static float[] unitVector() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return vector;
    }
}
package com.healthrecon.rag.service;

import com.healthrecon.rag.config.GoldenProperties;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.GoldenStatus;
import com.healthrecon.rag.domain.StoredDocument;
import com.healthrecon.rag.repository.DocumentRepository;
import com.healthrecon.rag.repository.DocumentSetRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.langchain4j.data.message.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldenCaseGeneratorTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentSetRepository documentSetRepository;
    @Mock
    private GoldenCaseService goldenCaseService;

    private GoldenCaseGenerator generator;

    private final UUID docSetId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        generator = new GoldenCaseGenerator(
                new GoldenProperties(true, 60000, 0, 3, 12000, 3),
                chatModel, documentRepository, documentSetRepository,
                goldenCaseService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void stubOwner() {
        when(documentSetRepository.findById(docSetId)).thenReturn(Optional.of(
                new DocumentSet(docSetId, ownerId, "Docs", null, null, Instant.now(), Instant.now())));
    }

    @Test
    void happyPathMarksDoneAndSavesCases() throws Exception {
        stubOwner();
        String json = """
                [{"question":"Q1?","answer":"A1"},{"question":"Q2?","answer":"A2"},{"question":"Q3?","answer":"A3"}]
                """;
        when(chatModel.chat((List<ChatMessage>) any(List.class))).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage(json)).build());
        when(goldenCaseService.toJson(any())).thenReturn("[]");
        StoredDocument doc = new DocumentBuilder(docId, docSetId).extractedText("Some content").build();

        generator.generate(doc);

        assertThat(doc.getGoldenStatus()).isEqualTo(GoldenStatus.DONE);
        verify(goldenCaseService, org.mockito.Mockito.times(3))
                .saveGenerated(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void blankExtractedTextMarksDoneWithoutSaving() {
        StoredDocument doc = new DocumentBuilder(docId, docSetId).extractedText("").build();

        generator.generate(doc);

        assertThat(doc.getGoldenStatus()).isEqualTo(GoldenStatus.DONE);
        verify(goldenCaseService, never()).saveGenerated(any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void invalidJsonMarksDocumentFailed() {
        when(chatModel.chat((List<ChatMessage>) any(List.class))).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage("Here is a nice answer for you!")).build());
        StoredDocument doc = new DocumentBuilder(docId, docSetId).extractedText("Some content").build();

        generator.generate(doc);

        assertThat(doc.getGoldenStatus()).isEqualTo(GoldenStatus.FAILED);
        assertThat(doc.getGoldenError()).containsIgnoringCase("Unrecognized token");
    }

    @Test
    void stripsFencedCodeBlockBeforeParsing() throws Exception {
        stubOwner();
        String json = """
                Here you go:
                ```json
                [{"question":"Q?","answer":"A"}]
                ```
                """;
        when(chatModel.chat((List<ChatMessage>) any(List.class))).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage(json)).build());
        when(goldenCaseService.toJson(any())).thenReturn("[]");
        StoredDocument doc = new DocumentBuilder(docId, docSetId).extractedText("Some content").build();

        generator.generate(doc);

        assertThat(doc.getGoldenStatus()).isEqualTo(GoldenStatus.DONE);
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(goldenCaseService).saveGenerated(any(), any(), any(), questionCaptor.capture(), anyString(), anyString());
        assertThat(questionCaptor.getValue()).isEqualTo("Q?");
    }

    @Test
    void filtersBlankQuestionOrAnswerPairs() throws Exception {
        stubOwner();
        String json = """
                [{"question":"Q?","answer":"A"},{"question":"  ","answer":"X"},{"question":"Q2?","answer":"  "}]
                """;
        when(chatModel.chat((List<ChatMessage>) any(List.class))).thenReturn(
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(new dev.langchain4j.data.message.AiMessage(json)).build());
        when(goldenCaseService.toJson(any())).thenReturn("[]");
        StoredDocument doc = new DocumentBuilder(docId, docSetId).extractedText("Some content").build();

        generator.generate(doc);

        assertThat(doc.getGoldenStatus()).isEqualTo(GoldenStatus.DONE);
        verify(goldenCaseService, org.mockito.Mockito.times(1))
                .saveGenerated(any(), any(), any(), anyString(), anyString(), anyString());
    }

    private static class DocumentBuilder {
        private final StoredDocument doc;

        DocumentBuilder(UUID id, UUID docSetId) {
            doc = StoredDocument.builder()
                    .id(id)
                    .docSetId(docSetId)
                    .filename("test.md")
                    .contentLength(0)
                    .sha256("abc")
                    .content(new byte[0])
                    .status(com.healthrecon.rag.domain.DocumentStatus.READY)
                    .createdAt(Instant.now())
                    .build();
        }

        DocumentBuilder extractedText(String text) {
            doc.markExtracting(Instant.now());
            doc.markReady(text);
            return this;
        }

        StoredDocument build() {
            return doc;
        }
    }
}
package com.healthrecon.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.ChatResponse;
import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.domain.ChatMessage;
import com.healthrecon.rag.domain.Conversation;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.repository.ChatMessageRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private DocumentSetService documentSetService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ChatModel chatModel;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private VectorIndexer vectorIndexer;

    private final UUID docSetId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(documentSetService, conversationService, chatMessageRepository,
                chatModel, embeddingModel, vectorIndexer, properties(), new ObjectMapper());
    }

    @Test
    void answersUsingRetrievedContextAndPersistsBothMessages() {
        readySet();
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f})));
        when(vectorIndexer.search(any(), any(), anyInt())).thenReturn(List.of(
                new ChunkSearchHit(UUID.randomUUID(), "guide.md", "Intro > Dosage", 0.9, "Take 500mg daily.")));
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("Take 500mg daily.")).build());
        when(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());

        ChatResponse response = service.chat(docSetId, conversationId, "What dosage?");

        assertThat(response.answer()).isEqualTo("Take 500mg daily.");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).filename()).isEqualTo("guide.md");
        verify(chatMessageRepository, times(2)).save(any(ChatMessage.class));

        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(promptCaptor.capture());
        List<dev.langchain4j.data.message.ChatMessage> prompt = promptCaptor.getValue();
        assertThat(prompt.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) prompt.get(0)).text()).contains("Take 500mg daily.");
        assertThat(prompt.get(prompt.size() - 1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void includesRecentHistoryInPrompt() {
        readySet();
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        when(vectorIndexer.search(any(), any(), anyInt())).thenReturn(List.of());
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("ok")).build());
        ChatMessage priorUser = new ChatMessage(UUID.randomUUID(), conversationId, ChatMessage.Role.USER,
                "prior question", null, Instant.now());
        ChatMessage priorAssistant = new ChatMessage(UUID.randomUUID(), conversationId, ChatMessage.Role.ASSISTANT,
                "prior answer", "[]", Instant.now());
        when(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(priorUser, priorAssistant));

        service.chat(docSetId, conversationId, "follow up");

        ArgumentCaptor<List<dev.langchain4j.data.message.ChatMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatModel).chat(promptCaptor.capture());
        List<dev.langchain4j.data.message.ChatMessage> prompt = promptCaptor.getValue();
        assertThat(prompt).hasSize(4); // system + 2 history + current
        assertThat(prompt.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) prompt.get(1)).singleText()).isEqualTo("prior question");
    }

    @Test
    void rejectsChatWhenSetIsNotReady() {
        DocumentSet set = new DocumentSet(docSetId, UUID.randomUUID(), "Docs", null,
                DocumentSetStatus.UPLOADING, Instant.now(), Instant.now());
        when(documentSetService.requireOwnedSet(docSetId)).thenReturn(set);

        assertThatThrownBy(() -> service.chat(docSetId, conversationId, "hello"))
                .isInstanceOf(ConflictException.class);
        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
    }

    @Test
    void renamesConversationFromFirstMessageWhenTitleIsDefault() {
        DocumentSet set = new DocumentSet(docSetId, UUID.randomUUID(), "Docs", null,
                DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetService.requireOwnedSet(docSetId)).thenReturn(set);
        Conversation conversation = new Conversation(conversationId, docSetId, UUID.randomUUID(),
                Conversation.DEFAULT_TITLE, Instant.now(), Instant.now());
        when(conversationService.requireOwned(docSetId, conversationId)).thenReturn(conversation);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        when(vectorIndexer.search(any(), any(), anyInt())).thenReturn(List.of());
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("ok")).build());
        when(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());

        ChatResponse response = service.chat(docSetId, conversationId, "What dosage?");

        assertThat(response.title()).isEqualTo("What dosage");
        assertThat(conversation.getTitle()).isEqualTo("What dosage");
    }

    @Test
    void keepsCustomTitleAndReturnsNull() {
        readySet();
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(new Embedding(new float[]{0.1f})));
        when(vectorIndexer.search(any(), any(), anyInt())).thenReturn(List.of());
        when(chatModel.chat(anyList())).thenReturn(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(new AiMessage("ok")).build());
        when(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of());

        ChatResponse response = service.chat(docSetId, conversationId, "follow up");

        assertThat(response.title()).isNull();
    }

    private void readySet() {
        DocumentSet set = new DocumentSet(docSetId, UUID.randomUUID(), "Docs", null,
                DocumentSetStatus.READY, Instant.now(), Instant.now());
        when(documentSetService.requireOwnedSet(docSetId)).thenReturn(set);
        Conversation conversation = new Conversation(conversationId, docSetId, UUID.randomUUID(),
                "Title", Instant.now(), Instant.now());
        when(conversationService.requireOwned(docSetId, conversationId)).thenReturn(conversation);
    }

    private static RagProperties properties() {
        return new RagProperties(5, 10, "You are a helpful assistant.",
                new RagProperties.Chunking("adaptive", 2000, 100, 2000));
    }
}
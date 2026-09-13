package com.healthrecon.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.ChatResponse;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.config.RagProperties;
import com.healthrecon.rag.domain.ChatMessage;
import com.healthrecon.rag.domain.Conversation;
import com.healthrecon.rag.domain.DocumentSet;
import com.healthrecon.rag.domain.DocumentSetStatus;
import com.healthrecon.rag.exception.ConflictException;
import com.healthrecon.rag.repository.ChatMessageRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DocumentSetService documentSetService;
    private final ConversationService conversationService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorIndexer vectorIndexer;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public ChatService(DocumentSetService documentSetService,
                       ConversationService conversationService,
                       ChatMessageRepository chatMessageRepository,
                       ChatModel chatModel,
                       EmbeddingModel embeddingModel,
                       VectorIndexer vectorIndexer,
                       RagProperties ragProperties,
                       ObjectMapper objectMapper) {
        this.documentSetService = documentSetService;
        this.conversationService = conversationService;
        this.chatMessageRepository = chatMessageRepository;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.vectorIndexer = vectorIndexer;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChatResponse chat(UUID docSetId, UUID conversationId, String message) {
        DocumentSet set = documentSetService.requireOwnedSet(docSetId);
        if (set.getStatus() != DocumentSetStatus.READY) {
            throw new ConflictException("Chat is only available once the document set is ready");
        }
        Conversation conversation = conversationService.requireOwned(docSetId, conversationId);
        Instant now = Instant.now();

        List<dev.langchain4j.data.message.ChatMessage> history =
                toLangchainHistory(chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId));

        ChatMessage userMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                ChatMessage.Role.USER, message, null, now);
        chatMessageRepository.save(userMessage);
        conversation.touch(now);

        Embedding query = embeddingModel.embed(message).content();
        List<ChunkSearchHit> hits = vectorIndexer.search(docSetId, query, ragProperties.topK());
        List<SourceResponse> sources = hits.stream()
                .map(hit -> new SourceResponse(hit.docId(), hit.filename(), hit.section(), truncate(hit.snippet(), 600)))
                .toList();

        List<dev.langchain4j.data.message.ChatMessage> prompt = new ArrayList<>();
        prompt.add(new SystemMessage(buildSystemPrompt(sources.isEmpty() ? List.of() : sources, ragProperties)));
        prompt.addAll(history);
        prompt.add(new UserMessage(message));

        String answer = chatModel.chat(prompt).aiMessage().text();
        String sourcesJson = toJson(sources);

        ChatMessage assistantMessage = new ChatMessage(UUID.randomUUID(), conversationId,
                ChatMessage.Role.ASSISTANT, answer, sourcesJson, Instant.now());
        chatMessageRepository.save(assistantMessage);

        String renamedTitle = renameConversationIfUntitled(conversation, message, now);

        return new ChatResponse(conversationId, assistantMessage.getId(), answer, sources, renamedTitle);
    }

    String renameConversationIfUntitled(Conversation conversation, String message, Instant now) {
        if (!Conversation.DEFAULT_TITLE.equals(conversation.getTitle())) {
            return null;
        }
        String derived = ConversationTitler.derive(message);
        if (derived == null) {
            return null;
        }
        conversation.rename(derived, now);
        return derived;
    }

    private List<dev.langchain4j.data.message.ChatMessage> toLangchainHistory(List<ChatMessage> stored) {
        int window = Math.max(1, ragProperties.maxHistoryMessages());
        List<ChatMessage> recent = stored.size() <= window ? stored : stored.subList(stored.size() - window, stored.size());
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        for (ChatMessage message : recent) {
            if (message.getRole() == ChatMessage.Role.USER) {
                messages.add(new UserMessage(message.getContent()));
            } else {
                messages.add(new AiMessage(message.getContent()));
            }
        }
        return messages;
    }

    private String buildSystemPrompt(List<SourceResponse> sources, RagProperties props) {
        StringBuilder prompt = new StringBuilder(props.systemPrompt());
        if (sources.isEmpty()) {
            return prompt.toString();
        }
        prompt.append("\n\nUse the following context to answer:\n");
        int i = 1;
        for (SourceResponse source : sources) {
            prompt.append("\n[").append(i++).append("] ")
                    .append(source.filename())
                    .append(source.section() == null || source.section().isBlank() ? "" : " > " + source.section())
                    .append("\n")
                    .append(source.snippet())
                    .append("\n");
        }
        return prompt.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize chat sources", e);
            return "[]";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max).stripTrailing() + "…";
    }
}
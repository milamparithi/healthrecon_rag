package com.healthrecon.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrecon.rag.api.dto.ChatMessageResponse;
import com.healthrecon.rag.api.dto.ConversationResponse;
import com.healthrecon.rag.api.dto.SourceResponse;
import com.healthrecon.rag.domain.ChatMessage;
import com.healthrecon.rag.domain.Conversation;
import com.healthrecon.rag.exception.NotFoundException;
import com.healthrecon.rag.repository.ChatMessageRepository;
import com.healthrecon.rag.repository.ConversationRepository;
import com.healthrecon.rag.security.CurrentUser;
import com.healthrecon.rag.security.CurrentUserSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final TypeReference<List<SourceResponse>> SOURCES_TYPE = new TypeReference<>() {
    };

    private final DocumentSetService documentSetService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public ConversationService(DocumentSetService documentSetService,
                               ConversationRepository conversationRepository,
                               ChatMessageRepository chatMessageRepository,
                               ObjectMapper objectMapper) {
        this.documentSetService = documentSetService;
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(UUID docSetId) {
        documentSetService.requireOwnedSet(docSetId);
        CurrentUser user = CurrentUserSupport.require();
        return conversationRepository.findAllByOwnerIdAndDocSetIdOrderByCreatedAtDesc(user.id(), docSetId).stream()
                .map(ConversationResponse::from)
                .toList();
    }

    @Transactional
    public ConversationResponse create(UUID docSetId, String title) {
        documentSetService.requireOwnedSet(docSetId);
        CurrentUser user = CurrentUserSupport.require();
        Instant now = Instant.now();
        String resolvedTitle = title == null || title.isBlank() ? Conversation.DEFAULT_TITLE : title.strip();
        Conversation conversation = new Conversation(
                UUID.randomUUID(), docSetId, user.id(), resolvedTitle, now, now);
        return ConversationResponse.from(conversationRepository.save(conversation));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(UUID docSetId, UUID conversationId) {
        requireOwned(docSetId, conversationId);
        return chatMessageRepository.findAllByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void delete(UUID docSetId, UUID conversationId) {
        Conversation conversation = requireOwned(docSetId, conversationId);
        conversationRepository.delete(conversation);
    }

    @Transactional(readOnly = true)
    public Conversation requireOwned(UUID docSetId, UUID conversationId) {
        documentSetService.requireOwnedSet(docSetId);
        CurrentUser user = CurrentUserSupport.require();
        return conversationRepository.findByIdAndOwnerIdAndDocSetId(conversationId, user.id(), docSetId)
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
    }

    private ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole().name().toLowerCase(),
                message.getContent(),
                parseSources(message.getSources()),
                message.getCreatedAt());
    }

    List<SourceResponse> parseSources(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(sourcesJson, SOURCES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
package com.healthrecon.rag.repository;

import com.healthrecon.rag.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findAllByConversationIdOrderByCreatedAtAsc(UUID conversationId);
}
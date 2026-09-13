package com.healthrecon.rag.repository;

import com.healthrecon.rag.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByIdAndOwnerIdAndDocSetId(UUID id, UUID ownerId, UUID docSetId);

    List<Conversation> findAllByOwnerIdAndDocSetIdOrderByCreatedAtDesc(UUID ownerId, UUID docSetId);
}
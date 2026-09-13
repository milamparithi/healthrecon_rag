package com.healthrecon.rag.repository;

import com.healthrecon.rag.domain.DocumentSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentSetRepository extends JpaRepository<DocumentSet, UUID> {

    List<DocumentSet> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<DocumentSet> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByOwnerIdAndName(UUID ownerId, String name);
}
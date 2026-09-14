package com.healthrecon.rag.repository;

import com.healthrecon.rag.domain.GoldenCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoldenCaseRepository extends JpaRepository<GoldenCase, UUID> {

    List<GoldenCase> findAllByDocSetIdOrderByCreatedAtDesc(UUID docSetId);

    Optional<GoldenCase> findByIdAndOwnerIdAndDocSetId(UUID id, UUID ownerId, UUID docSetId);

    long countByDocSetId(UUID docSetId);

    long countByDocSetIdAndStatus(UUID docSetId, String status);

    void deleteByDocSetId(UUID docSetId);
}
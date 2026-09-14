package com.healthrecon.rag.repository;

import com.healthrecon.rag.domain.AnswerEval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AnswerEvalRepository extends JpaRepository<AnswerEval, UUID>, JpaSpecificationExecutor<AnswerEval> {

    Optional<AnswerEval> findByIdAndDocSetId(UUID id, UUID docSetId);

    long countByDocSetId(UUID docSetId);

    long countByDocSetIdAndReviewStatus(UUID docSetId, String reviewStatus);

    long countByDocSetIdAndSampledTrue(UUID docSetId);

    long countByDocSetIdAndAutoFlagsIsNotNull(UUID docSetId);

    long countByDocSetIdAndVerdict(UUID docSetId, String verdict);

    @Query("select avg(e.rating) from AnswerEval e where e.docSetId = :docSetId and e.rating is not null")
    Double averageRating(@Param("docSetId") UUID docSetId);
}
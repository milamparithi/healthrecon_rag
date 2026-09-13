package com.healthrecon.rag.repository;

import com.healthrecon.rag.domain.DocumentStatus;
import com.healthrecon.rag.domain.IndexStatus;
import com.healthrecon.rag.domain.StoredDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<StoredDocument, UUID> {

    Optional<StoredDocument> findByIdAndDocSetId(UUID id, UUID docSetId);

    boolean existsByDocSetIdAndSha256(UUID docSetId, String sha256);

    List<StoredDocument> findAllByStatus(DocumentStatus status);

    List<StoredDocument> findAllByStatusAndIndexStatusIn(DocumentStatus status, Collection<IndexStatus> indexStatuses);

    long countByDocSetId(UUID docSetId);

    long countByDocSetIdAndStatus(UUID docSetId, DocumentStatus status);

    void deleteByDocSetId(UUID docSetId);

    @Query("""
            select d.id as id, d.filename as filename, d.contentType as contentType,
                   d.contentLength as contentLength, d.status as status, d.error as error,
                   d.createdAt as createdAt
            from StoredDocument d
            where d.docSetId = :docSetId
            order by d.createdAt
            """)
    Page<DocumentListItem> listByDocSetId(@Param("docSetId") UUID docSetId, Pageable pageable);

    @Query("""
            select coalesce(sum(d.contentLength), 0)
            from StoredDocument d, DocumentSet s
            where s.id = d.docSetId
              and s.ownerId = :ownerId
            """)
    long sumContentLengthByOwner(@Param("ownerId") UUID ownerId);

    interface DocumentListItem {
        UUID getId();

        String getFilename();

        String getContentType();

        long getContentLength();

        DocumentStatus getStatus();

        String getError();

        java.time.Instant getCreatedAt();
    }
}
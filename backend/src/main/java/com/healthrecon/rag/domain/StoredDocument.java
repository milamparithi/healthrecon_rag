package com.healthrecon.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document",
        uniqueConstraints = @UniqueConstraint(name = "uq_document_doc_set_sha", columnNames = {"doc_set_id", "sha256"}))
public class StoredDocument {

    @Id
    private UUID id;

    @Column(name = "doc_set_id", nullable = false)
    private UUID docSetId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "content_length", nullable = false)
    private long contentLength;

    @Column(nullable = false)
    private String sha256;

    @Column(nullable = false)
    private byte[] content;

    @Column(name = "extracted_text")
    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false)
    private IndexStatus indexStatus = IndexStatus.NOT_INDEXED;

    @Column(name = "index_error")
    private String indexError;

    @Enumerated(EnumType.STRING)
    @Column(name = "golden_status", nullable = false)
    private GoldenStatus goldenStatus = GoldenStatus.PENDING;

    @Column(name = "golden_error")
    private String goldenError;

    @Column(name = "golden_attempts", nullable = false)
    private int goldenAttempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StoredDocument() {
    }

    private StoredDocument(Builder builder) {
        this.id = builder.id;
        this.docSetId = builder.docSetId;
        this.filename = builder.filename;
        this.contentType = builder.contentType;
        this.contentLength = builder.contentLength;
        this.sha256 = builder.sha256;
        this.content = builder.content;
        this.extractedText = builder.extractedText;
        this.indexStatus = builder.indexStatus == null ? IndexStatus.NOT_INDEXED : builder.indexStatus;
        this.indexError = builder.indexError;
        this.goldenStatus = GoldenStatus.PENDING;
        this.goldenError = null;
        this.goldenAttempts = 0;
        this.status = builder.status;
        this.error = builder.error;
        this.createdAt = builder.createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocSetId() {
        return docSetId;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getSha256() {
        return sha256;
    }

    public byte[] getContent() {
        return content;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public IndexStatus getIndexStatus() {
        return indexStatus;
    }

    public String getIndexError() {
        return indexError;
    }

    public GoldenStatus getGoldenStatus() {
        return goldenStatus;
    }

    public String getGoldenError() {
        return goldenError;
    }

    public int getGoldenAttempts() {
        return goldenAttempts;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markExtracting(Instant now) {
        this.status = DocumentStatus.EXTRACTING;
        this.error = null;
    }

    public void markReady(String extractedText) {
        this.status = DocumentStatus.READY;
        this.extractedText = extractedText;
        this.error = null;
    }

    public void markIndexing(Instant now) {
        this.indexStatus = IndexStatus.INDEXING;
        this.indexError = null;
    }

    public void markIndexed() {
        this.indexStatus = IndexStatus.INDEXED;
        this.indexError = null;
    }

    public void markIndexFailed(String error) {
        this.indexStatus = IndexStatus.FAILED;
        this.indexError = error;
    }

    public void markFailed(String error) {
        this.status = DocumentStatus.FAILED;
        this.error = error;
    }

    public void markGoldenGenerating() {
        this.goldenStatus = GoldenStatus.GENERATING;
        this.goldenAttempts++;
        this.goldenError = null;
    }

    public void markGoldenDone() {
        this.goldenStatus = GoldenStatus.DONE;
        this.goldenError = null;
    }

    public void markGoldenFailed(String error) {
        this.goldenStatus = GoldenStatus.FAILED;
        this.goldenError = error;
    }

    public static class Builder {
        private UUID id;
        private UUID docSetId;
        private String filename;
        private String contentType;
        private long contentLength;
        private String sha256;
        private byte[] content;
        private String extractedText;
        private IndexStatus indexStatus;
        private String indexError;
        private DocumentStatus status;
        private String error;
        private Instant createdAt;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder docSetId(UUID docSetId) {
            this.docSetId = docSetId;
            return this;
        }

        public Builder filename(String filename) {
            this.filename = filename;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        public Builder sha256(String sha256) {
            this.sha256 = sha256;
            return this;
        }

        public Builder content(byte[] content) {
            this.content = content;
            return this;
        }

        public Builder extractedText(String extractedText) {
            this.extractedText = extractedText;
            return this;
        }

        public Builder indexStatus(IndexStatus indexStatus) {
            this.indexStatus = indexStatus;
            return this;
        }

        public Builder indexError(String indexError) {
            this.indexError = indexError;
            return this;
        }

        public Builder status(DocumentStatus status) {
            this.status = status;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public StoredDocument build() {
            return new StoredDocument(this);
        }
    }
}
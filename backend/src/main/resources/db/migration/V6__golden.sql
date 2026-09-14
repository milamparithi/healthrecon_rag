ALTER TABLE document
    ADD COLUMN golden_status TEXT NOT NULL DEFAULT 'PENDING',
    ADD COLUMN golden_error TEXT,
    ADD COLUMN golden_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_document_golden_status ON document (golden_status);

CREATE TABLE golden_case (
    id                UUID PRIMARY KEY,
    doc_set_id        UUID        NOT NULL REFERENCES document_set (id) ON DELETE CASCADE,
    owner_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_doc_id     UUID        REFERENCES document (id) ON DELETE SET NULL,
    question          TEXT        NOT NULL,
    reference_answer  TEXT        NOT NULL,
    expected_sources  TEXT        NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'DRAFT',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_golden_case_doc_set ON golden_case (doc_set_id);
CREATE INDEX idx_golden_case_owner ON golden_case (owner_id);
CREATE INDEX idx_golden_case_source_doc ON golden_case (source_doc_id);

UPDATE app_metadata SET value = 'phase-3-golden', updated_at = now() WHERE key = 'schema_version';
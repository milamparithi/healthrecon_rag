ALTER TABLE document
    ADD COLUMN index_status TEXT NOT NULL DEFAULT 'NOT_INDEXED',
    ADD COLUMN index_error TEXT;

CREATE INDEX idx_document_index_status ON document (index_status);

UPDATE app_metadata SET value = 'phase-2-index', updated_at = now() WHERE key = 'schema_version';
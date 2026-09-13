CREATE TABLE document_set (
    id          UUID PRIMARY KEY,
    owner_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    description TEXT,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_set_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_document_set_owner ON document_set (owner_id);

CREATE TABLE document (
    id             UUID PRIMARY KEY,
    doc_set_id     UUID        NOT NULL REFERENCES document_set (id) ON DELETE CASCADE,
    filename       TEXT        NOT NULL,
    content_type   TEXT,
    content_length BIGINT      NOT NULL,
    sha256         TEXT        NOT NULL,
    content        BYTEA       NOT NULL,
    extracted_text TEXT,
    status         TEXT        NOT NULL,
    error          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_doc_set_sha UNIQUE (doc_set_id, sha256)
);

CREATE INDEX idx_document_doc_set ON document (doc_set_id);

INSERT INTO app_metadata (key, value, updated_at)
VALUES ('schema_version', 'phase-1', now())
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at;
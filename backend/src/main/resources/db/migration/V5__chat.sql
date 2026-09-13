CREATE TABLE conversation (
    id         UUID PRIMARY KEY,
    doc_set_id UUID        NOT NULL REFERENCES document_set (id) ON DELETE CASCADE,
    owner_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversation_doc_set ON conversation (doc_set_id);
CREATE INDEX idx_conversation_owner ON conversation (owner_id);

CREATE TABLE chat_message (
    id              UUID PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    role            TEXT        NOT NULL,
    content         TEXT        NOT NULL,
    sources         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_message_conversation ON chat_message (conversation_id);

UPDATE app_metadata SET value = 'phase-2-chat', updated_at = now() WHERE key = 'schema_version';
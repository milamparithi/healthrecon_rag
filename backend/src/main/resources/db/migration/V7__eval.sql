CREATE TABLE answer_eval (
    id                UUID PRIMARY KEY,
    doc_set_id        UUID        NOT NULL REFERENCES document_set (id) ON DELETE CASCADE,
    chat_message_id   UUID,
    conversation_id   UUID,
    question          TEXT        NOT NULL,
    answer            TEXT        NOT NULL,
    sources           TEXT        NOT NULL,
    origin            TEXT        NOT NULL DEFAULT 'CHAT',
    auto_flags        TEXT,
    coverage_score    DOUBLE PRECISION,
    sampled           BOOLEAN     NOT NULL DEFAULT FALSE,
    review_status     TEXT        NOT NULL DEFAULT 'PENDING',
    verdict           TEXT,
    rating            INTEGER,
    comment           TEXT,
    corrected_answer  TEXT,
    reviewed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_answer_eval_doc_set   ON answer_eval (doc_set_id);
CREATE INDEX idx_answer_eval_review_status ON answer_eval (review_status);
CREATE INDEX idx_answer_eval_created_at ON answer_eval (created_at);
CREATE INDEX idx_answer_eval_sampled ON answer_eval (sampled) WHERE sampled = TRUE;
CREATE INDEX idx_answer_eval_flagged ON answer_eval (doc_set_id) WHERE auto_flags IS NOT NULL;

UPDATE app_metadata SET value = 'phase-4-eval', updated_at = now() WHERE key = 'schema_version';
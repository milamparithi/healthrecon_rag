CREATE TABLE app_metadata (
    key        VARCHAR(255) PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_metadata (key, value) VALUES ('schema_version', 'phase-0');
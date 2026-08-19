-- Flyway tracks what has run in its own schema history table, so a migration is
-- applied exactly once and never has to be written to survive re-running.
-- Never edit a migration that has already run anywhere: add V2__... instead.
--
-- Replace the example table with your own schema.

CREATE TABLE IF NOT EXISTS items (
    id         BIGSERIAL    PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS items_created_at_idx ON items (created_at DESC);

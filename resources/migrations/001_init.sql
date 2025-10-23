BEGIN;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS entities (
    entity_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    entity_type TEXT NOT NULL,
    doc JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CHECK (jsonb_typeof(doc) = 'object')
);

CREATE INDEX IF NOT EXISTS entities_user_type_idx
    ON entities (user_id, entity_type);

CREATE INDEX IF NOT EXISTS entities_updated_at_idx
    ON entities (updated_at DESC);

CREATE INDEX IF NOT EXISTS entities_doc_gin_idx
    ON entities USING GIN (doc);

INSERT INTO schema_migrations (version)
SELECT '001_init'
WHERE NOT EXISTS (
    SELECT 1 FROM schema_migrations WHERE version = '001_init'
);

COMMIT;

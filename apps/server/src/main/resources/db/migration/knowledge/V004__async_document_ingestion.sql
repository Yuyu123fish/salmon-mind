-- Feature 003 Stage 02：在 V002 预留表上前向演进，不修改已执行的旧 migration。

ALTER TABLE knowledge_source_revisions
    DROP CONSTRAINT IF EXISTS knowledge_source_revisions_format_check;

ALTER TABLE knowledge_source_revisions
    ADD CONSTRAINT knowledge_source_revisions_format_check
        CHECK (format IN ('TEXT', 'MARKDOWN', 'PDF', 'DOCX'));

ALTER TABLE knowledge_source_revisions
    ADD COLUMN IF NOT EXISTS size_bytes BIGINT NOT NULL DEFAULT 0
        CHECK (size_bytes >= 0),
    ADD COLUMN IF NOT EXISTS detected_media_type TEXT NOT NULL DEFAULT 'text/plain'
        CHECK (btrim(detected_media_type) <> ''),
    ADD COLUMN IF NOT EXISTS page_count INTEGER
        CHECK (page_count IS NULL OR page_count >= 0),
    ADD COLUMN IF NOT EXISTS text_char_count INTEGER
        CHECK (text_char_count IS NULL OR text_char_count >= 0);

ALTER TABLE knowledge_index_generations
    DROP CONSTRAINT IF EXISTS knowledge_index_generations_revision_count_check,
    DROP CONSTRAINT IF EXISTS knowledge_index_generations_evidence_count_check;

ALTER TABLE knowledge_index_generations
    ADD COLUMN IF NOT EXISTS embedding_provider TEXT NOT NULL DEFAULT 'siliconflow'
        CHECK (btrim(embedding_provider) <> ''),
    ADD COLUMN IF NOT EXISTS chunk_version VARCHAR(32) NOT NULL DEFAULT 'chunk-v1'
        CHECK (btrim(chunk_version) <> ''),
    ADD COLUMN IF NOT EXISTS mapping_version VARCHAR(32) NOT NULL DEFAULT 'mapping-v1'
        CHECK (btrim(mapping_version) <> ''),
    ALTER COLUMN revision_count SET DEFAULT 0,
    ALTER COLUMN evidence_count SET DEFAULT 0;

ALTER TABLE knowledge_index_generations
    ADD CONSTRAINT knowledge_index_generations_revision_count_check CHECK (revision_count >= 0),
    ADD CONSTRAINT knowledge_index_generations_evidence_count_check CHECK (evidence_count >= 0);

CREATE TABLE knowledge_ingestion_jobs (
    id UUID PRIMARY KEY,
    source_revision_id UUID NOT NULL REFERENCES knowledge_source_revisions (id) ON DELETE RESTRICT,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'PENDING_DISPATCH', 'QUEUED', 'PARSING', 'EMBEDDING', 'INDEXING',
        'READY', 'OCR_REQUIRED', 'FAILED'
    )),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    error_message TEXT,
    stream_message_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    UNIQUE (source_revision_id, attempt_number)
);

CREATE INDEX knowledge_ingestion_jobs_dispatch_idx
    ON knowledge_ingestion_jobs (state, updated_at);

CREATE INDEX knowledge_ingestion_jobs_revision_idx
    ON knowledge_ingestion_jobs (source_revision_id, attempt_number DESC);

ALTER TABLE knowledge_evidence
    ADD COLUMN IF NOT EXISTS source_revision_sha256 CHAR(64)
        CHECK (source_revision_sha256 IS NULL OR source_revision_sha256 ~ '^[0-9a-f]{64}$');

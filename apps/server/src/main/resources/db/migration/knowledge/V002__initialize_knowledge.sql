CREATE TABLE knowledge_sources (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE RESTRICT,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    kind VARCHAR(32) NOT NULL CHECK (kind IN (
        'PROJECT', 'DOCUMENT', 'NOTE', 'RESUME', 'JOB_DESCRIPTION'
    )),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE knowledge_source_revisions (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES knowledge_sources (id) ON DELETE RESTRICT,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    format VARCHAR(16) NOT NULL CHECK (format IN ('TEXT', 'MARKDOWN')),
    media_type TEXT NOT NULL CHECK (btrim(media_type) <> ''),
    content_object_key TEXT NOT NULL UNIQUE CHECK (btrim(content_object_key) <> ''),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (source_id, revision_number)
);

CREATE TABLE knowledge_index_generations (
    id UUID PRIMARY KEY,
    physical_index TEXT NOT NULL UNIQUE CHECK (btrim(physical_index) <> ''),
    status VARCHAR(16) NOT NULL CHECK (status IN ('BUILDING', 'ACTIVE', 'FAILED', 'RETIRED')),
    embedding_model TEXT NOT NULL CHECK (btrim(embedding_model) <> ''),
    embedding_dimensions INTEGER NOT NULL CHECK (embedding_dimensions > 0),
    revision_count INTEGER NOT NULL CHECK (revision_count > 0),
    evidence_count INTEGER NOT NULL CHECK (evidence_count > 0),
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX knowledge_single_active_generation_idx
    ON knowledge_index_generations ((1))
    WHERE status = 'ACTIVE';

CREATE TABLE knowledge_evidence (
    id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES knowledge_index_generations (id) ON DELETE CASCADE,
    source_revision_id UUID NOT NULL REFERENCES knowledge_source_revisions (id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    location TEXT NOT NULL CHECK (btrim(location) <> ''),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    char_count INTEGER NOT NULL CHECK (char_count > 0),
    UNIQUE (generation_id, source_revision_id, ordinal)
);

CREATE INDEX knowledge_evidence_generation_idx
    ON knowledge_evidence (generation_id, id);

-- Feature 005 Stage 02：Source 删除生命周期；旧记录只回填为 ACTIVE，不触碰外部存储。

ALTER TABLE knowledge_sources
    ADD COLUMN lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT knowledge_sources_lifecycle_check
        CHECK (lifecycle IN ('ACTIVE', 'DELETING'));

CREATE INDEX knowledge_sources_workspace_lifecycle_created_idx
    ON knowledge_sources (workspace_id, lifecycle, created_at DESC);

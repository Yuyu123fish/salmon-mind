CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id),
    title VARCHAR(120) NOT NULL,
    history_format_version INTEGER NOT NULL DEFAULT 1,
    active_leaf_entry_id UUID NULL,
    last_confirmed_seq BIGINT NOT NULL DEFAULT 0,
    latest_compaction_entry_id UUID NULL,
    latest_compaction_seq BIGINT NULL,
    latest_compaction_byte_offset BIGINT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_conversations_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_conversations_last_confirmed_seq_nonneg CHECK (last_confirmed_seq >= 0),
    -- 三个压缩索引字段必须同时为空或同时非空
    CONSTRAINT ck_conversations_compaction_all_or_none CHECK (
        (latest_compaction_entry_id IS NULL AND latest_compaction_seq IS NULL AND latest_compaction_byte_offset IS NULL)
        OR
        (latest_compaction_entry_id IS NOT NULL AND latest_compaction_seq IS NOT NULL AND latest_compaction_byte_offset IS NOT NULL)
    ),
    CONSTRAINT ck_conversations_compaction_seq_nonneg CHECK (
        latest_compaction_seq IS NULL OR latest_compaction_seq >= 0
    )
);

-- trigger_entry_id 不是跨存储外键：JSONL 可能先于数据库存在对应 Entry
CREATE TABLE conversation_runs (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    trigger_entry_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(60) NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NULL,
    CONSTRAINT ck_conversation_runs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT ck_conversation_runs_error_code_not_blank CHECK (error_code IS NULL OR btrim(error_code) <> '')
);

-- 每个 Conversation 最多一个 RUNNING Run，防止绕过队列产生并发冲突
CREATE UNIQUE INDEX uq_conversation_runs_one_running
    ON conversation_runs (conversation_id)
    WHERE status = 'RUNNING';

CREATE INDEX ix_conversations_workspace_updated
    ON conversations (workspace_id, updated_at DESC);

CREATE INDEX ix_conversation_runs_conversation_started
    ON conversation_runs (conversation_id, started_at DESC);

CREATE INDEX ix_conversation_runs_trigger
    ON conversation_runs (trigger_entry_id);

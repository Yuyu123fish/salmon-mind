CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    singleton_key SMALLINT NOT NULL DEFAULT 1,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workspaces_singleton UNIQUE (singleton_key),
    CONSTRAINT ck_workspaces_singleton_key CHECK (singleton_key = 1),
    CONSTRAINT ck_workspaces_name_not_blank CHECK (btrim(name) <> '')
);

INSERT INTO workspaces (id, singleton_key, name)
VALUES ('00000000-0000-0000-0000-000000000001', 1, 'My Workspace');

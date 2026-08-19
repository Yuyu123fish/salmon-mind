ALTER TABLE knowledge_source_revisions
    ADD COLUMN parsed_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE knowledge_source_revisions
    ADD CONSTRAINT knowledge_source_revisions_parsed_metadata_object
    CHECK (jsonb_typeof(parsed_metadata) = 'object');

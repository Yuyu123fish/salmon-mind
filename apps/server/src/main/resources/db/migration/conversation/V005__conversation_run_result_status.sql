ALTER TABLE conversation_runs
    ADD COLUMN result_status VARCHAR(32) NULL;

UPDATE conversation_runs
SET result_status = 'COMPLETE'
WHERE status = 'SUCCEEDED' AND result_status IS NULL;

ALTER TABLE conversation_runs
    ADD CONSTRAINT ck_conversation_runs_result_status CHECK (
        (status = 'SUCCEEDED' AND result_status IN ('COMPLETE', 'INCOMPLETE_LENGTH'))
        OR
        (status IN ('RUNNING', 'FAILED', 'INTERRUPTED') AND result_status IS NULL)
    );

ALTER TABLE label_qc_tasks
    ADD COLUMN IF NOT EXISTS review_request_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_label_qc_task_review_request
    ON label_qc_tasks(factory_id, review_request_id)
    WHERE review_request_id IS NOT NULL;

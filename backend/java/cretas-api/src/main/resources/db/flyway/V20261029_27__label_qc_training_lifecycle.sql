ALTER TABLE label_qc_tasks
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived_by BIGINT,
    ADD COLUMN archived_at TIMESTAMP,
    ADD COLUMN training_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN training_decided_by BIGINT,
    ADD COLUMN training_decided_at TIMESTAMP,
    ADD COLUMN training_decision_notes VARCHAR(500),
    ADD COLUMN backup_exported_by BIGINT,
    ADD COLUMN backup_exported_at TIMESTAMP;

ALTER TABLE label_qc_tasks
    ADD CONSTRAINT chk_label_qc_training_status
    CHECK (training_status IN ('PENDING', 'APPROVED', 'REJECTED'));

CREATE INDEX idx_label_qc_task_archive
    ON label_qc_tasks(factory_id, archived, updated_at);

CREATE INDEX idx_label_qc_task_training
    ON label_qc_tasks(factory_id, training_status, reviewed_at);

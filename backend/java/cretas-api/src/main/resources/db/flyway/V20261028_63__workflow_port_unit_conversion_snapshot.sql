ALTER TABLE workflow_task_ports
    ADD COLUMN IF NOT EXISTS unit_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS conversion_ref_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS conversion_version BIGINT,
    ADD COLUMN IF NOT EXISTS conversion_factor_snapshot NUMERIC(20,8);

UPDATE workflow_task_ports
SET unit_code = unit
WHERE unit_code IS NULL;

ALTER TABLE workflow_task_ports
    ALTER COLUMN unit_code SET NOT NULL;

ALTER TABLE product_process_workflows
    ADD COLUMN IF NOT EXISTS unit_review_required BOOLEAN NOT NULL DEFAULT FALSE;

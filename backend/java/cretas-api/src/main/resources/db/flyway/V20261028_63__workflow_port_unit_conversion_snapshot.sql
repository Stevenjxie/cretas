ALTER TABLE workflow_task_ports
    -- Legacy runtime.unit allowed 32 characters. Keep the snapshot mirror equally wide so
    -- deployment cannot fail on an already-materialized custom/unknown legacy value.
    -- Newly compiled rows are still canonical unit codes (<= 20) by application contract.
    ADD COLUMN IF NOT EXISTS unit_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS material_primary_unit_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS conversion_ref_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS conversion_version BIGINT,
    ADD COLUMN IF NOT EXISTS conversion_from_unit_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS conversion_to_unit_code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS conversion_factor_snapshot NUMERIC(20,8),
    ADD COLUMN IF NOT EXISTS port_to_primary_factor_snapshot NUMERIC(20,8);

UPDATE workflow_task_ports
SET unit_code = unit
WHERE unit_code IS NULL;

ALTER TABLE workflow_task_ports
    ALTER COLUMN unit_code SET NOT NULL;

ALTER TABLE product_process_workflows
    ADD COLUMN IF NOT EXISTS unit_review_required BOOLEAN NOT NULL DEFAULT FALSE;

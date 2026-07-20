ALTER TABLE work_processes
    ADD COLUMN IF NOT EXISTS merged_into_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS merged_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS merged_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS governance_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_wp_factory_selectable
    ON work_processes(factory_id, is_active, merged_into_id)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS work_process_governance_audits (
    id VARCHAR(50) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    master_process_id VARCHAR(50) NOT NULL,
    governed_process_ids TEXT NOT NULL,
    operator VARCHAR(100) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_wp_governance_factory_key UNIQUE (factory_id, idempotency_key),
    CONSTRAINT chk_wp_governance_mode CHECK (mode IN ('MERGE', 'DEACTIVATE_OTHERS'))
);

CREATE INDEX IF NOT EXISTS idx_wp_governance_factory_created
    ON work_process_governance_audits(factory_id, created_at DESC)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN work_processes.unit IS
    'Legacy compatibility only. Units are authoritative on Workflow node/production snapshots.';
COMMENT ON COLUMN work_processes.output_unit IS
    'Legacy compatibility only. Output units are authoritative on Workflow node/production snapshots.';
COMMENT ON COLUMN work_processes.sort_order IS
    'Legacy compatibility only. Execution order is authoritative on Workflow steps.';

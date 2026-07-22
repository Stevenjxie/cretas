ALTER TABLE purchase_requisitions
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_no VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_pr_source
    ON purchase_requisitions(factory_id, source_type, source_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pr_source_identity
    ON purchase_requisitions(factory_id, source_type, source_id)
    WHERE source_type IS NOT NULL AND source_id IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN purchase_requisitions.source_type IS
    'Structured demand origin, for example PRODUCTION_PLAN_SHORTAGE';
COMMENT ON COLUMN purchase_requisitions.source_id IS
    'Immutable source entity id used for idempotency and traceability';
COMMENT ON COLUMN purchase_requisitions.source_no IS
    'Display-only source business number snapshot';

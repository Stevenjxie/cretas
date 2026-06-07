-- T121: 工序多人负责 — join 表 + 任一负责人可报工
-- Keeps existing responsible_worker_id column as primary/AI-draft value.
-- This join table allows N responsible workers per product_work_process row.
-- Any one of the N assignees (or the fallback responsible_worker_id) may submit a yield report.

CREATE TABLE IF NOT EXISTS product_work_process_assignees (
    id                      BIGSERIAL PRIMARY KEY,
    product_work_process_id BIGINT NOT NULL REFERENCES product_work_processes(id) ON DELETE CASCADE,
    worker_id               BIGINT NOT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP NULL
);

-- Unique: one (pwp, worker) pair per soft-delete lifecycle slot.
-- We enforce uniqueness on non-deleted rows via partial unique index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pwp_assignee_active
    ON product_work_process_assignees (product_work_process_id, worker_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_pwp_assignees_pwp
    ON product_work_process_assignees (product_work_process_id)
    WHERE deleted_at IS NULL;

-- Trigger to auto-update updated_at
CREATE OR REPLACE FUNCTION update_pwp_assignees_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_pwp_assignees_updated_at ON product_work_process_assignees;
CREATE TRIGGER trg_pwp_assignees_updated_at
    BEFORE UPDATE ON product_work_process_assignees
    FOR EACH ROW EXECUTE FUNCTION update_pwp_assignees_updated_at();

-- Backfill: migrate existing single-assignee steps into the join table.
-- ON CONFLICT DO NOTHING makes this safe to re-run.
INSERT INTO product_work_process_assignees (product_work_process_id, worker_id)
SELECT id, responsible_worker_id
FROM   product_work_processes
WHERE  responsible_worker_id IS NOT NULL
ON CONFLICT DO NOTHING;

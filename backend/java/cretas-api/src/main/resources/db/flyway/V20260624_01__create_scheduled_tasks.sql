-- Canvas-Cron Phase 5 — scheduled_tasks table (DB-driven cron config).
-- Sister chat will fill DynamicScheduler impl + AI Tool bodies; this table
-- backs all of them. See docs/superpowers/specs/2026-05-18-canvas-cron-phase5-spec.md §2.

CREATE TABLE scheduled_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- NULL = global task (cross-factory); non-NULL = per-factory.
  factory_id VARCHAR(50),

  task_code VARCHAR(100) NOT NULL,
  task_name VARCHAR(255),

  -- Spring cron expression (6 fields: sec min hour day month dow).
  -- e.g. '0 0 9 1 * ?' = 1st of every month at 09:00:00.
  cron_expression VARCHAR(100) NOT NULL,

  -- Spring bean name implementing com.cretas.aims.service.cron.TaskHandler.
  handler_bean_name VARCHAR(255) NOT NULL,

  enabled BOOLEAN NOT NULL DEFAULT TRUE,

  last_run_at TIMESTAMP,
  last_run_status VARCHAR(20),   -- SUCCESS / FAILED / RUNNING / SKIPPED
  last_run_error TEXT,

  -- BaseEntity audit columns (consistent with all other Cretas entities).
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

-- Partial unique indexes to enforce code-uniqueness per scope, ignoring soft-deleted rows.
-- Global tasks: task_code must be globally unique among non-deleted rows.
CREATE UNIQUE INDEX idx_scheduled_tasks_global_code
  ON scheduled_tasks (task_code)
  WHERE factory_id IS NULL AND deleted_at IS NULL;

-- Per-factory tasks: (factory_id, task_code) must be unique among non-deleted rows.
CREATE UNIQUE INDEX idx_scheduled_tasks_factory_code
  ON scheduled_tasks (factory_id, task_code)
  WHERE factory_id IS NOT NULL AND deleted_at IS NULL;

-- Bootstrap query in DynamicScheduler filters by enabled=true + not-deleted.
CREATE INDEX idx_scheduled_tasks_enabled
  ON scheduled_tasks (enabled)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE scheduled_tasks IS 'Canvas-Cron Phase 5 — DB-driven cron task configuration. DynamicScheduler loads on boot + on every mutation. Global (factory_id IS NULL) and per-factory variants both supported.';
COMMENT ON COLUMN scheduled_tasks.factory_id IS 'NULL = global task (cross-factory); non-NULL = per-factory.';
COMMENT ON COLUMN scheduled_tasks.cron_expression IS 'Spring cron 6-field format: sec min hour day month dow.';
COMMENT ON COLUMN scheduled_tasks.handler_bean_name IS 'Spring bean implementing com.cretas.aims.service.cron.TaskHandler.';

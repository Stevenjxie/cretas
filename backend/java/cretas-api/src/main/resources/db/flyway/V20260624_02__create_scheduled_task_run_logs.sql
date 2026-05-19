-- Canvas-Cron Phase 5 — scheduled_task_run_logs (execution history).
-- One row per task execution. Written by DynamicScheduler around each
-- handler.execute() call. See spec §2.

CREATE TABLE scheduled_task_run_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  task_id UUID NOT NULL REFERENCES scheduled_tasks(id),

  -- Denormalized for fast per-factory filter without JOIN.
  factory_id VARCHAR(50),

  started_at TIMESTAMP NOT NULL,
  finished_at TIMESTAMP,
  duration_ms BIGINT,

  status VARCHAR(20) NOT NULL,   -- TaskRunStatus enum: SUCCESS / FAILED / RUNNING / SKIPPED
  error_msg TEXT,

  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

-- Optimized for "show me last N runs of task X" query (UI history table + pagination).
CREATE INDEX idx_task_logs_task_started
  ON scheduled_task_run_logs (task_id, started_at DESC)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE scheduled_task_run_logs IS 'Canvas-Cron Phase 5 — execution history. Retention strategy: ~90 days (sister chat decides DB job vs dog-fooded ScheduledTask).';
COMMENT ON COLUMN scheduled_task_run_logs.status IS 'TaskRunStatus enum: SUCCESS / FAILED / RUNNING / SKIPPED.';

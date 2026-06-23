-- SP-F Task 1.1: 逐工序电子表格录入 — 行级追踪表
-- Source-of-truth for raw spreadsheet input (row_payload JSON) linked to a materialized ProductionBatch.
CREATE TABLE process_sheet_rows (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64) NOT NULL,
  process_code VARCHAR(32) NOT NULL,
  client_row_id VARCHAR(64) NOT NULL,
  batch_id BIGINT,
  batch_number VARCHAR(64),
  row_payload JSONB NOT NULL,
  row_status VARCHAR(16) NOT NULL DEFAULT 'SAVED',
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL,
  CONSTRAINT uk_sheet_row UNIQUE (factory_id, plan_id, process_code, client_row_id)
);
CREATE INDEX idx_psr_plan ON process_sheet_rows (factory_id, plan_id, process_code) WHERE deleted_at IS NULL;

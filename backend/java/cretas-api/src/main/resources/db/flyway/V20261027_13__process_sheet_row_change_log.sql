-- SP-G P3: 逐工序电子表格行级操作记录 (字段级 diff 审计时间线)
-- 镜像 config_change_log 字段级审计模式 (before/after JSONB + diff_summary),
-- 增加 process_sheet_rows 行追踪所需的定位列 (plan_id / process_code / client_row_id)。
CREATE TABLE process_sheet_row_change_log (
  id BIGSERIAL PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  plan_id VARCHAR(64) NOT NULL,
  process_code VARCHAR(32) NOT NULL,
  client_row_id VARCHAR(64) NOT NULL,
  operation VARCHAR(16) NOT NULL,          -- CREATE / UPDATE / DELETE
  before_value JSONB,
  after_value JSONB,
  diff_summary TEXT,
  operator_id BIGINT,
  created_at TIMESTAMP DEFAULT NOW()
);

-- 行级时间线读取主索引: (factory, plan, process, client_row) 定位某一行的全部变更。
CREATE INDEX idx_psrcl_row
  ON process_sheet_row_change_log (factory_id, plan_id, process_code, client_row_id);

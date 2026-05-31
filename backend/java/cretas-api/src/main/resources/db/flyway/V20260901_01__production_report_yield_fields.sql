-- V20260901_01__production_report_yield_fields.sql
--
-- 报工体系统一 Phase A — 给现有 production_reports 表加报工事实层字段。
--
-- Background:
--   production_reports 已存在(process-mode 报工在用: PROGRESS/HOURS report_type)。
--   Phase A 新增 report_type='YIELD' 的逐道工序报工,需要"投入+产出双量"以算单工序出成率。
--   spec: docs/superpowers/specs/2026-05-31-报工体系统一-design.md §3.1 §9.2 §9.3 §9.10
--
-- 与现有列复用关系(不重复加):
--   operator → 现有 worker_id;  工时 → 现有 total_work_minutes;
--   报工时间 → 现有 created_at;  标签 → 现有 custom_fields;  产出 → 现有 output_quantity。
--
-- 精度对齐现有 output_quantity = NUMERIC(12,2)(同表一致)。
-- IF NOT EXISTS 保证幂等。

ALTER TABLE production_reports
  ADD COLUMN IF NOT EXISTS work_process_task_id   BIGINT,
  ADD COLUMN IF NOT EXISTS process_order          INTEGER,
  ADD COLUMN IF NOT EXISTS product_type_id        VARCHAR(100),
  ADD COLUMN IF NOT EXISTS input_quantity         NUMERIC(12,2),
  ADD COLUMN IF NOT EXISTS input_unit             VARCHAR(16),
  ADD COLUMN IF NOT EXISTS output_unit            VARCHAR(16),
  ADD COLUMN IF NOT EXISTS carryover_quantity     NUMERIC(12,2),
  ADD COLUMN IF NOT EXISTS source_batch_refs      JSONB,
  ADD COLUMN IF NOT EXISTS warehouse_out_quantity NUMERIC(12,2),
  ADD COLUMN IF NOT EXISTS feed_in_quantity       NUMERIC(12,2),
  ADD COLUMN IF NOT EXISTS intermediate_batch_no  VARCHAR(64),
  ADD COLUMN IF NOT EXISTS settled                BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS settled_at             TIMESTAMP;

-- 按批次+工序聚合出成率的查询索引(YIELD 模式)
CREATE INDEX IF NOT EXISTS idx_pr_yield_batch_order
  ON production_reports (factory_id, batch_id, process_order)
  WHERE report_type = 'YIELD' AND deleted_at IS NULL;

-- 工序批次号唯一(张权 A6 防重),仅对非空值生效(partial unique)
CREATE UNIQUE INDEX IF NOT EXISTS uq_pr_intermediate_batch_no
  ON production_reports (factory_id, intermediate_batch_no)
  WHERE intermediate_batch_no IS NOT NULL AND deleted_at IS NULL;

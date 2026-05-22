-- V20260822_00__ai_invocation_metadata.sql
--
-- Sprint 10 P0 shared infra — add ai_invocation_metadata jsonb column to 4 entity tables.
-- 用于标记由 Sprint 10 AI Workdesk 1-click 创建的记录, 便于 cleanup + 观察 AI 闭环成功率.
--
-- Schema: {source: "sprint-10-loop-N", testRun: bool, createdAt: iso, ...optional}
-- NULL = manual UI create (传统菜单 path)
--
-- 5 Sprint 10 闭环对应 4 table (Loop 4 审批 复用 approval_workflow_instances.context_json):
--   Loop 1 发货 → sales_delivery_records
--   Loop 2 入库 → purchase_receive_records
--   Loop 3 采购 → purchase_orders
--   Loop 4 审批 → approval_workflow_instances.context_json (existing, no new column needed)
--   Loop 5 生产 → production_batches

ALTER TABLE sales_delivery_records
    ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
COMMENT ON COLUMN sales_delivery_records.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag. Schema: {source: sprint-10-loop-N, testRun: bool, createdAt: iso}. NULL = manual UI create.';

ALTER TABLE purchase_receive_records
    ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
COMMENT ON COLUMN purchase_receive_records.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag (same schema as sales_delivery_records).';

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
COMMENT ON COLUMN purchase_orders.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag.';

ALTER TABLE production_batches
    ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
COMMENT ON COLUMN production_batches.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag.';

-- Indexes for cleanup query (gin jsonb_path_ops 适合 @> containment check)
CREATE INDEX IF NOT EXISTS idx_sdr_ai_test
    ON sales_delivery_records USING gin (ai_invocation_metadata jsonb_path_ops)
    WHERE ai_invocation_metadata IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_prr_ai_test
    ON purchase_receive_records USING gin (ai_invocation_metadata jsonb_path_ops)
    WHERE ai_invocation_metadata IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_po_ai_test
    ON purchase_orders USING gin (ai_invocation_metadata jsonb_path_ops)
    WHERE ai_invocation_metadata IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pb_ai_test
    ON production_batches USING gin (ai_invocation_metadata jsonb_path_ops)
    WHERE ai_invocation_metadata IS NOT NULL;

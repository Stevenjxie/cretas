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
--
-- ⚠️ 表存在守卫 (2026-06-01 修 e2e-pr-gate 全新 CI DB 启动失败):
--   这 4 张表都是 Hibernate JPA entity (ddl-auto=update 建), 不由 Flyway CREATE。全新 DB
--   (CI) 上 Flyway 在 Hibernate ddl-auto 之前跑 → 表还不存在, 裸 `ALTER TABLE
--   sales_delivery_records` 报 "relation does not exist" (ADD COLUMN IF NOT EXISTS 只守列
--   不守表) → 阻断 Spring 启动 → e2e-pr-gate "Backend not responding" (每月每分支全红)。
--   to_regclass 守卫: 表存在才 ALTER/INDEX; 不存在则跳过, Hibernate 随后按 entity 建表+列
--   (entity 已声明 ai_invocation_metadata jsonb 列, ddl-auto=update 会补)。
--   prod 无影响: 该 migration 数月前已 apply 进 flyway_schema_history + validate-on-migrate
--   =false → prod Flyway 不会重跑也不校验 checksum, 编辑仅影响尚未跑过它的全新 DB (CI)。

DO $$
BEGIN
    IF to_regclass('public.sales_delivery_records') IS NOT NULL THEN
        ALTER TABLE sales_delivery_records ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
        COMMENT ON COLUMN sales_delivery_records.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag. Schema: {source: sprint-10-loop-N, testRun: bool, createdAt: iso}. NULL = manual UI create.';
        CREATE INDEX IF NOT EXISTS idx_sdr_ai_test
            ON sales_delivery_records USING gin (ai_invocation_metadata jsonb_path_ops)
            WHERE ai_invocation_metadata IS NOT NULL;
    END IF;

    IF to_regclass('public.purchase_receive_records') IS NOT NULL THEN
        ALTER TABLE purchase_receive_records ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
        COMMENT ON COLUMN purchase_receive_records.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag (same schema as sales_delivery_records).';
        CREATE INDEX IF NOT EXISTS idx_prr_ai_test
            ON purchase_receive_records USING gin (ai_invocation_metadata jsonb_path_ops)
            WHERE ai_invocation_metadata IS NOT NULL;
    END IF;

    IF to_regclass('public.purchase_orders') IS NOT NULL THEN
        ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
        COMMENT ON COLUMN purchase_orders.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag.';
        CREATE INDEX IF NOT EXISTS idx_po_ai_test
            ON purchase_orders USING gin (ai_invocation_metadata jsonb_path_ops)
            WHERE ai_invocation_metadata IS NOT NULL;
    END IF;

    IF to_regclass('public.production_batches') IS NOT NULL THEN
        ALTER TABLE production_batches ADD COLUMN IF NOT EXISTS ai_invocation_metadata JSONB;
        COMMENT ON COLUMN production_batches.ai_invocation_metadata IS 'Sprint 10 AI invocation source tag.';
        CREATE INDEX IF NOT EXISTS idx_pb_ai_test
            ON production_batches USING gin (ai_invocation_metadata jsonb_path_ops)
            WHERE ai_invocation_metadata IS NOT NULL;
    END IF;
END $$;

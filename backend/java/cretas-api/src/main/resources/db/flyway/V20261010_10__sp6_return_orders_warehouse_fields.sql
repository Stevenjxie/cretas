-- SP6 采购到付款 — return_orders 仓库执行字段
-- 超收退货流程需要记录仓库执行时间、执行人及关联批次
-- 幂等: ADD COLUMN IF NOT EXISTS

ALTER TABLE return_orders
    ADD COLUMN IF NOT EXISTS warehouse_executed_at TIMESTAMP    NULL,
    ADD COLUMN IF NOT EXISTS warehouse_executed_by BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS batch_ids             JSONB        NULL;

COMMENT ON COLUMN return_orders.warehouse_executed_at IS 'SP6: 仓库实际执行退货时间';
COMMENT ON COLUMN return_orders.warehouse_executed_by IS 'SP6: 仓库执行人 user_id';
COMMENT ON COLUMN return_orders.batch_ids             IS 'SP6: 关联的物料批次 ID 列表 ["batchId1","batchId2"]';

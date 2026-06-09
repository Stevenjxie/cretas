-- SP5: 生产计划添加关联订单ID列表 (JSONB)
-- source_order_ids: 该计划覆盖的销售订单ID数组, 支持合并计划场景
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS source_order_ids JSONB NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN production_plans.source_order_ids IS 'SP5 关联销售订单ID列表 (JSONB string array)';

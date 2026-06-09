-- SP2: 生产计划二次加工来源类型字段
-- plan_source_type: NORMAL(默认) | SECONDARY(跨单独立二次加工)
-- secondary_source_wip_id: 跨单二次加工时领用哪条 SemiFinishedInventory 记录
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS plan_source_type VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS secondary_source_wip_id BIGINT REFERENCES semi_finished_inventory(id);

COMMENT ON COLUMN production_plans.plan_source_type IS 'NORMAL=正常生产计划 | SECONDARY=二次加工独立单(来源SemiFinishedInventory)';
COMMENT ON COLUMN production_plans.secondary_source_wip_id IS '跨单二次加工: FK→semi_finished_inventory.id; 开工时从该记录扣减 availableQuantity';

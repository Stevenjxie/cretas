-- V20261023_01: 为 production_plans.source_order_ids (JSONB) 添加 GIN 索引
-- 支持 SP5 多 SO 合并工单的双向检索:
--   生产单 → 关联 SO:  SELECT source_order_ids FROM production_plans WHERE id = ?
--   SO → 生产单:       SELECT * FROM production_plans WHERE source_order_ids @> '["<so_id>"]'
-- 无 GIN 索引时后者需全表扫描; GIN 允许 O(log N) jsonb-contains 查找。
-- V20261011_12 已添加列 (ADD COLUMN IF NOT EXISTS), 本迁移只加索引 (幂等 IF NOT EXISTS)。

CREATE INDEX IF NOT EXISTS idx_production_plans_source_order_ids_gin
    ON production_plans USING GIN (source_order_ids);

COMMENT ON INDEX idx_production_plans_source_order_ids_gin
    IS 'SP5 GIN index for source_order_ids JSONB @> containment queries (SO→plan bidirectional search)';

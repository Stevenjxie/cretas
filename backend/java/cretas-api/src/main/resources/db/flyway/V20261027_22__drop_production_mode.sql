-- V20261027_22: 删除冗余的 production_mode 列
-- 库存生产 (BY_STOCK) 语义已由 source_type = 'SAFETY_STOCK' (存货生产) 覆盖，
-- production_mode 为重复字段，统一改为 gate on source_type = 'SAFETY_STOCK'。
-- 参见 service: InterimSettleServiceImpl + ProductionPlanServiceImpl stopProduction。

ALTER TABLE production_plans DROP COLUMN IF EXISTS production_mode;

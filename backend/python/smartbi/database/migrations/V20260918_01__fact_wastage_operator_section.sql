-- V20260918_01: Wave2 损耗按人/档口责任制 — fact_restaurant_wastage 加 operator_id + section_code
-- Mirrors Java flyway V20260918_01 (cretas_db.wastage_records.operator_id / section_code)。
-- 兑现邓总诉求: "今天损耗明天屌你" / "同样 1 万营业额哪个档口哪个人成本涨了" 按人/档口透明化。
--
-- ⚠️ 实施前已执行: git ls-tree origin/main backend/python/smartbi/database/migrations | grep V20260918 → 无碰撞
-- 幂等: ADD COLUMN IF NOT EXISTS; CREATE INDEX IF NOT EXISTS。表已存在 + RLS + GRANT 已就位（仅加列）。

ALTER TABLE fact_restaurant_wastage ADD COLUMN IF NOT EXISTS operator_id   BIGINT;
ALTER TABLE fact_restaurant_wastage ADD COLUMN IF NOT EXISTS section_code  VARCHAR(32);

COMMENT ON COLUMN fact_restaurant_wastage.operator_id  IS '损耗责任人 (cretas_db.users.id); 与录单人区分; null=未指定';
COMMENT ON COLUMN fact_restaurant_wastage.section_code IS '档口编码 SEAFOOD/COLD_DISH/HOT_DISH/FRONT_HOUSE/OTHER; null=未指定';

-- 按责任人 / 档口聚合查询索引
CREATE INDEX IF NOT EXISTS idx_fact_wastage_factory_operator
    ON fact_restaurant_wastage (factory_id, operator_id);
CREATE INDEX IF NOT EXISTS idx_fact_wastage_factory_section
    ON fact_restaurant_wastage (factory_id, section_code);

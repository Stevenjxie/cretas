-- #58 Phase 2 (margin): 目标毛利率配置表 — 单张配置表 + GRANT + RLS
-- 毛利 (margin = 营收 − 食材成本 COGS) pace 预警的目标毛利 = 营收目标 × target_margin_rate。
-- COGS / 毛利本身按需即时计算 (POS qty × agg_restaurant_product_cost.food_cost), 无新事实表。
-- 仅 target_margin_rate 需持久化 → 本表。
--
-- collision check (实施前): git ls-tree -r origin/main --name-only | grep smartbi/database/migrations/V20260925 → 0 (frontier V20260924)。
-- store_id 可空 (NULL = 全品牌/连锁汇总) → partial unique index 分两路 (PG 可能 <15, 不能依赖 NULLS NOT DISTINCT)。
-- GRANT DML 必须 (历史 grant gap 复发 3+ 次): SELECT/INSERT/UPDATE/DELETE + sequence。

CREATE TABLE IF NOT EXISTS restaurant_target_margin_config (
    id                 BIGSERIAL     PRIMARY KEY,
    factory_id         VARCHAR(50)   NOT NULL,
    store_id           BIGINT        REFERENCES dim_store(store_id) ON DELETE SET NULL,  -- NULL = 连锁汇总
    target_margin_rate NUMERIC(5,4)  NOT NULL DEFAULT 0.5500,    -- 目标毛利率 0..1 (默认 55%)
    updated_by         VARCHAR(50)   DEFAULT NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_margin_rate_range CHECK (target_margin_rate >= 0 AND target_margin_rate <= 1)
);
ALTER TABLE restaurant_target_margin_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_target_margin_config FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_target_margin_config;
CREATE POLICY tenant_isolation ON restaurant_target_margin_config FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 幂等: 同 (factory, store) 一条配置。store_id 可空 → partial unique index 两路。
CREATE UNIQUE INDEX IF NOT EXISTS uq_margin_config_store
    ON restaurant_target_margin_config (factory_id, store_id)
    WHERE store_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_margin_config_nostore
    ON restaurant_target_margin_config (factory_id)
    WHERE store_id IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_margin_config TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_target_margin_config_id_seq TO smartbi_user;

-- Rollback:
--   DROP TABLE IF EXISTS restaurant_target_margin_config;

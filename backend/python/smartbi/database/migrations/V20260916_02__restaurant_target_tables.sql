-- G2 餐饮目标拆分 + 达成率预警 — 两张表 + GRANT + 触发器
-- ⚠️ 实施前已执行: git ls-tree origin/main backend/python/smartbi/database/migrations | grep V20260604 → 无碰撞

-- ── restaurant_target_hierarchy: 四级目标值 ─────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_target_hierarchy (
    id                   BIGSERIAL       PRIMARY KEY,
    factory_id           VARCHAR(50)     NOT NULL,
    kpi_kind             VARCHAR(30)     NOT NULL,   -- 'revenue' | 'bill_count'
    level                VARCHAR(10)     NOT NULL,   -- 'year' | 'month' | 'week' | 'day'
    period_key           VARCHAR(20)     NOT NULL,   -- '2026', '2026-06', '2026-W23', '2026-06-03'
    store_id             BIGINT          REFERENCES dim_store(store_id) ON DELETE SET NULL,
    target_value         NUMERIC(18,2)   NOT NULL,
    distribution_weight  NUMERIC(5,4)    DEFAULT NULL,  -- 预留淡旺季权重 (MVP 不用)
    reason               VARCHAR(100)    DEFAULT NULL,  -- 调整原因 dropdown 值
    created_by           VARCHAR(50)     NOT NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_target_grain UNIQUE (factory_id, kpi_kind, level, period_key, store_id)
);
ALTER TABLE restaurant_target_hierarchy ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_target_hierarchy FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_target_hierarchy;
CREATE POLICY tenant_isolation ON restaurant_target_hierarchy FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_rth_factory_level_period
    ON restaurant_target_hierarchy (factory_id, level, period_key);

-- GRANT DML (必须 — 历史 2 次 grant gap 复发已固化此步骤)
GRANT INSERT, UPDATE, DELETE ON restaurant_target_hierarchy TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_target_hierarchy_id_seq TO smartbi_user;

-- updated_at 自动触发器
CREATE OR REPLACE FUNCTION rth_touch_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$;
DROP TRIGGER IF EXISTS trg_rth_touch ON restaurant_target_hierarchy;
CREATE TRIGGER trg_rth_touch BEFORE UPDATE ON restaurant_target_hierarchy
    FOR EACH ROW EXECUTE FUNCTION rth_touch_updated_at();

-- ── restaurant_alert_config: 预警阈值 ───────────────────────────────
CREATE TABLE IF NOT EXISTS restaurant_alert_config (
    id                   BIGSERIAL       PRIMARY KEY,
    factory_id           VARCHAR(50)     NOT NULL,
    kpi_kind             VARCHAR(30)     NOT NULL,
    level                VARCHAR(10)     NOT NULL,
    warn_threshold       NUMERIC(5,4)    NOT NULL DEFAULT 0.80,   -- 达成率低于此 → WARN
    critical_threshold   NUMERIC(5,4)    NOT NULL DEFAULT 0.60,   -- 低于此 → CRITICAL
    store_id             BIGINT          REFERENCES dim_store(store_id) ON DELETE SET NULL,
    created_by           VARCHAR(50)     NOT NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_alert_config_grain UNIQUE (factory_id, kpi_kind, level, store_id),
    CONSTRAINT chk_alert_thresholds CHECK (warn_threshold > critical_threshold)
);
ALTER TABLE restaurant_alert_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_alert_config FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_alert_config;
CREATE POLICY tenant_isolation ON restaurant_alert_config FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT INSERT, UPDATE, DELETE ON restaurant_alert_config TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_alert_config_id_seq TO smartbi_user;

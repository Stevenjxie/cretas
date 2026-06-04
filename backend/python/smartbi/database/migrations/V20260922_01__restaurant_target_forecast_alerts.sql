-- #58 Phase 1 (revenue-only): 滚动预测 + 实时 pace 预警 — 两张表 + GRANT + RLS
-- 扩展已上线 G2 (V20260916_02 restaurant_target_hierarchy + restaurant_alert_config)。
-- 本 migration NOT touch restaurant_target_hierarchy (G2 拥有), 只新增 forecast + alerts 两张物化表。
--
-- collision check (实施前): git ls-tree -r origin/main --name-only | grep smartbi/database/migrations/V20260922 → 0 (frontier V20260920)。
-- store_id 可空 (NULL = 全品牌/连锁汇总) → partial unique index 分两路 (PG 可能 <15, 不能依赖 NULLS NOT DISTINCT)。
-- GRANT DML 必须 (历史 grant gap 复发 3+ 次): SELECT/INSERT/UPDATE/DELETE + sequence。

-- ── restaurant_target_forecast: 滚动预测 (gold daily 趋势外推) ──────────────────
CREATE TABLE IF NOT EXISTS restaurant_target_forecast (
    id              BIGSERIAL       PRIMARY KEY,
    factory_id      VARCHAR(50)     NOT NULL,
    store_id        BIGINT          REFERENCES dim_store(store_id) ON DELETE SET NULL,  -- NULL = 连锁汇总
    forecast_date   DATE            NOT NULL,                  -- 被预测的那一天
    forecast_amount NUMERIC(18,2)   NOT NULL,                  -- 点估计 (营业额)
    lower_bound     NUMERIC(18,2)   NOT NULL,                  -- 80% CI 下界
    upper_bound     NUMERIC(18,2)   NOT NULL,                  -- 80% CI 上界
    model_type      VARCHAR(30)     NOT NULL DEFAULT 'linear_trend',
    anchor_date     DATE            NOT NULL,                  -- 拟合窗口的最后一天 (历史锚点)
    window_days     INT             NOT NULL,                  -- 拟合用的历史天数
    version         BIGINT          NOT NULL DEFAULT 1,        -- 每次重算 +1, 留旧版本审计
    computed_at     TIMESTAMP       NOT NULL DEFAULT NOW()
);
ALTER TABLE restaurant_target_forecast ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_target_forecast FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_target_forecast;
CREATE POLICY tenant_isolation ON restaurant_target_forecast FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_rtf_factory_date
    ON restaurant_target_forecast (factory_id, forecast_date);
-- 幂等: 同 (factory, store, date, version) 唯一。store_id 可空 → partial unique index 两路。
CREATE UNIQUE INDEX IF NOT EXISTS uq_forecast_store
    ON restaurant_target_forecast (factory_id, store_id, forecast_date, version)
    WHERE store_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_forecast_nostore
    ON restaurant_target_forecast (factory_id, forecast_date, version)
    WHERE store_id IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_forecast TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_target_forecast_id_seq TO smartbi_user;

-- ── restaurant_target_alerts: 实时 pace 预警 (周期内进度 vs 时间进度) ───────────
CREATE TABLE IF NOT EXISTS restaurant_target_alerts (
    id              BIGSERIAL       PRIMARY KEY,
    factory_id      VARCHAR(50)     NOT NULL,
    period_type     VARCHAR(10)     NOT NULL,                  -- 'month' | 'week' | 'day' | 'year'
    period_key      VARCHAR(20)     NOT NULL,                  -- '2026-06' / '2026-W23' / ...
    store_id        BIGINT          REFERENCES dim_store(store_id) ON DELETE SET NULL,  -- NULL = 连锁汇总
    alert_level     VARCHAR(10)     NOT NULL,                  -- 'OK' | 'WARN' | 'CRIT'
    actual_amount   NUMERIC(18,2),                             -- 周期内累计实际 (data_missing → NULL)
    target_amount   NUMERIC(18,2),                             -- 周期目标值
    completion_pct  NUMERIC(7,4),                              -- actual / target (0..n)
    elapsed_pct     NUMERIC(7,4),                              -- 已过天数 / 总天数 (0..1)
    pace_gap_pct    NUMERIC(8,4),                              -- completion_pct - elapsed_pct (负 = 落后)
    suppressed_until DATE           DEFAULT NULL,              -- 管理员静默到期日 (NULL = 未静默)
    acknowledged_by VARCHAR(50)     DEFAULT NULL,
    computed_at     TIMESTAMP       NOT NULL DEFAULT NOW()
);
ALTER TABLE restaurant_target_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_target_alerts FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON restaurant_target_alerts;
CREATE POLICY tenant_isolation ON restaurant_target_alerts FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
CREATE INDEX IF NOT EXISTS idx_rta_factory_period
    ON restaurant_target_alerts (factory_id, period_type, period_key);
-- 幂等: 同 (factory, period_type, period_key, store) 一条最新预警。store_id 可空 → partial index 两路。
CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_store
    ON restaurant_target_alerts (factory_id, period_type, period_key, store_id)
    WHERE store_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_nostore
    ON restaurant_target_alerts (factory_id, period_type, period_key)
    WHERE store_id IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_alerts TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_target_alerts_id_seq TO smartbi_user;

-- Per-daypart staffing/labor-efficiency table (RESTAURANT_OPS_STAFFING_ADVICE,
-- 2026-07-08 restaurant intent tiered-routing follow-up). No prior table
-- captures per-timeslot in-store headcount, so this is a new fact table
-- (unlike the sibling inventory-warning seed, which reuses existing tables).
--
-- Grain: one row per (factory, store_id nullable, daypart, weekday_type).
-- store_id NULL = factory-level rollup (used by the DEMO_REST seed in the
-- follow-up migration; per-store granularity is left open for later ETL
-- without a schema change).
--
-- RLS pattern mirrors dim_ingredient_threshold (V20260427_02): FORCE ROW
-- LEVEL SECURITY + tenant_isolation policy keyed off the same
-- `app.factory_id` GUC every restaurant-ops resolver already sets via
-- `SELECT set_config('app.factory_id', $1, true)` before querying.

CREATE TABLE IF NOT EXISTS fact_staffing_daypart (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    store_id BIGINT,
    daypart VARCHAR(20) NOT NULL,       -- 午市/晚市/下午茶/夜宵
    weekday_type VARCHAR(10) NOT NULL,  -- weekday/weekend
    avg_orders NUMERIC(10,2),           -- 该时段历史日均订单
    staff_on_duty INT,                  -- 当前配置在岗人数
    target_orders_per_staff NUMERIC(6,2), -- 目标人效(每人每时段订单)
    UNIQUE (factory_id, store_id, daypart, weekday_type)
);

ALTER TABLE fact_staffing_daypart ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_staffing_daypart FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_staffing_daypart;
CREATE POLICY tenant_isolation ON fact_staffing_daypart
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

GRANT SELECT, INSERT, UPDATE, DELETE ON fact_staffing_daypart TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE fact_staffing_daypart_id_seq TO smartbi_user;

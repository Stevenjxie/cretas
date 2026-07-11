-- Gold aggregate — daily void counts by store/staff/reason.
--
-- Source: fact_pos_void (V20261005_01). Materialized by
-- materialize_daily_void() (smartbi/services/materialized_analytics/daily_void.py).
-- Read by smartbi.gold.queries.void_rate / void_audit via
-- GET /api/smartbi/gold/void-rate.
--
-- staff_id / void_reason are nullable at the Silver (fact_pos_void) grain,
-- but PRIMARY KEY columns cannot contain NULL. Mirrors the existing
-- agg_daily_order_type_meal COALESCE-to-sentinel pattern (order_type /
-- meal_period -> '未分类'):
--   staff_id NULL    -> 0        (0 = unattributed; 操作人 blank in ~0.3% of
--                                  reference rows). Deliberately NO FK to
--                                  dim_staff on this Gold table — the 0
--                                  sentinel would violate a real FK, and
--                                  agg_daily itself only FKs to dim_store
--                                  (no dim_staff FK precedent for Gold
--                                  aggregates in this codebase either).
--   void_reason NULL/blank -> '未标注' (honest "uncategorized" bucket, not
--                                  fabricated — matches the ~65% blank rate
--                                  seen in the real 二维火 export; POS staff
--                                  frequently don't fill in a reason).
CREATE TABLE IF NOT EXISTS agg_daily_void (
    factory_id    VARCHAR(50) NOT NULL,
    date          DATE NOT NULL,
    store_id      BIGINT NOT NULL,
    staff_id      BIGINT NOT NULL DEFAULT 0,
    void_reason   VARCHAR(200) NOT NULL DEFAULT '未标注',
    void_count    INT NOT NULL DEFAULT 0,
    version       BIGINT NOT NULL DEFAULT 1,
    computed_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, date, store_id, staff_id, void_reason),
    CONSTRAINT fk_agg_daily_void_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE CASCADE
);
ALTER TABLE agg_daily_void ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_daily_void FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_daily_void;
CREATE POLICY tenant_isolation ON agg_daily_void FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
-- Hot path: "void rate over date range" (SUM void_count grouped by nothing/store).
CREATE INDEX IF NOT EXISTS idx_agg_daily_void_factory_date
    ON agg_daily_void (factory_id, date);

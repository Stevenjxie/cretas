-- Gold aggregate — daily 区域坪效 (in-store dining-zone revenue/efficiency)
-- by store/zone.
--
-- Source: fact_zone_sales (V20261006_01). Materialized by
-- materialize_daily_zone() (smartbi/services/materialized_analytics/daily_zone.py).
-- Read by smartbi.gold.queries.zone_efficiency via
-- GET /api/smartbi/gold/zone-efficiency.
--
-- zone_name is nullable at the Silver (fact_zone_sales) grain conceptually,
-- but PRIMARY KEY columns cannot contain NULL. Mirrors the existing
-- agg_daily_void COALESCE-to-sentinel pattern (void_reason -> '未标注'):
--   zone_name NULL/blank -> '未分区' (honest "unclassified zone" bucket —
--   matches fact_zone_sales's own DEFAULT sentinel, not fabricated).
--
-- ⚠️ revenue = SUM(折后金额) (actual amount collected, not pre-discount).
-- item_qty is NUMERIC, not INT — source 数量 column has occasional
-- non-integer quantities (weighed items); rounding to INT would silently
-- lose real fractional units.
--
-- ⚠️ No floor-area/seat-count column exists anywhere in the source export,
-- so revenue/item_qty here is an EFFICIENCY PROXY, not a true
-- revenue-per-square-meter 坪效. See zone_efficiency()'s docstring caveat —
-- surfaced honestly to the API caller, never fabricated as a real 元/平米 figure.
CREATE TABLE IF NOT EXISTS agg_daily_zone (
    factory_id    VARCHAR(50) NOT NULL,
    date          DATE NOT NULL,
    store_id      BIGINT NOT NULL,
    zone_name     VARCHAR(200) NOT NULL DEFAULT '未分区',
    revenue       NUMERIC(14,2) NOT NULL DEFAULT 0,
    item_qty      NUMERIC(12,2) NOT NULL DEFAULT 0,
    line_count    INT NOT NULL DEFAULT 0,
    version       BIGINT NOT NULL DEFAULT 1,
    computed_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (factory_id, date, store_id, zone_name),
    CONSTRAINT fk_agg_daily_zone_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE CASCADE
);
ALTER TABLE agg_daily_zone ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_daily_zone FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_daily_zone;
CREATE POLICY tenant_isolation ON agg_daily_zone FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
-- Hot path: "zone revenue over date range" (SUM revenue/item_qty grouped by zone_name).
CREATE INDEX IF NOT EXISTS idx_agg_daily_zone_factory_date
    ON agg_daily_zone (factory_id, date);

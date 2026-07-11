-- Silver fact table — 区域销售 (in-store dining-zone sales) line grain.
--
-- New restaurant analytics dimension: 区域坪效 (in-store dining-zone
-- revenue/efficiency) — greenfield, zero prior zone-sales handling in this
-- codebase (RegionAnalysisService / RegionSummaryWriter are a DIFFERENT,
-- unrelated "region" concept: geographic province/city, not in-store dining
-- zones. RegionSummaryWriter is a deliberate stub for a geographic-region
-- report that Phase C explicitly scoped OUT — see that file's module
-- docstring. This migration does not touch or resurrect that path).
--
-- Source: 二维火 "区域销售报表" export — one row per (date, store, zone,
-- product) sales line. Columns (verified against a real 青花椒 customer
-- export, ~200k rows across a full year, ~118k in the 2025-01..07 window):
-- 日期/门店名称/区域名称/商品编码/商品分类/商品名称/单价/数量/折前金额/折后金额.
-- Same preamble-rows-before-header shape as other 二维火 exports
-- (smartbi/ingestion/pos_ingest.py:_detect_header_row already handles this
-- via the 门店名称 marker; the export also appends a trailing 合计 (grand
-- total) footer row with blank store/zone — the loader skips it).
--
-- ⚠️ 区域名称 (zone_name) is the IN-STORE DINING ZONE (大厅/小桌/中桌/大桌/...),
-- NOT a geographic region. Some real values are delivery-channel labels
-- rather than physical space (无桌位(美团外卖)/无桌位(饿了么外卖)/
-- 无桌位(京东外卖)/外卖/京东/饿了么) — stored as-is (data fidelity); the
-- Gold read endpoint surfaces an honest caveat rather than silently
-- reclassifying them.
--
-- ⚠️ Idempotency key: this report carries no bill_no/order_id — a line is
-- the atomic unit. We derive a source_row_hash (sha256 over the raw column
-- values) as the dedup key, mirroring the existing source_row_hash pattern
-- in fact_review_event (V20260428_01) / finance_writer.py's voucher rows.
-- This lets a rerun of the loader be safe (ON CONFLICT DO NOTHING) without
-- inventing a synthetic sequential id that would make every rerun "unique".
CREATE TABLE IF NOT EXISTS fact_zone_sales (
    id                      BIGSERIAL PRIMARY KEY,
    factory_id              VARCHAR(50) NOT NULL,
    upload_id               BIGINT,               -- loose link to upload tracking
    source_type             VARCHAR(20) NOT NULL DEFAULT 'excel',
    store_id                BIGINT NOT NULL,
    zone_name               VARCHAR(200) NOT NULL DEFAULT '未分区', -- 区域名称; blank -> sentinel (see note above; ~1/200k blank in reference export)
    product_name             VARCHAR(300),          -- 商品名称 (nullable — informational only, no FK/dim_product resolution needed for the zone-grain Gold aggregate)
    unit_price               NUMERIC(12,2),         -- 单价
    quantity                 NUMERIC(12,2),         -- 数量 (NUMERIC not INT — reference export has a handful of non-integer quantities, e.g. weighed items)
    amount_before_discount    NUMERIC(14,2),         -- 折前金额
    amount_after_discount     NUMERIC(14,2),         -- 折后金额 (actual revenue collected — what agg_daily_zone.revenue sums)
    date                      DATE NOT NULL,         -- 日期 (the operational sales date)
    source_row_hash           VARCHAR(64) NOT NULL,  -- sha256(factory_id|store_name|zone_name|product_name|date|unit_price|quantity|amount_after_discount) — see loader
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_fact_zone_sales UNIQUE (factory_id, source_type, source_row_hash),
    CONSTRAINT fk_fact_zone_sales_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE RESTRICT
);
ALTER TABLE fact_zone_sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_zone_sales FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_zone_sales;
CREATE POLICY tenant_isolation ON fact_zone_sales FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
DROP TRIGGER IF EXISTS trg_fact_zone_sales_touch ON fact_zone_sales;
CREATE TRIGGER trg_fact_zone_sales_touch BEFORE UPDATE ON fact_zone_sales
    FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
-- Hot path A: "zone revenue by store/day" — agg_daily_zone materializer source scan.
CREATE INDEX IF NOT EXISTS idx_fact_zone_sales_factory_date_store
    ON fact_zone_sales (factory_id, date, store_id);
-- Hot path B: upload rollback ("delete everything from upload 1234").
CREATE INDEX IF NOT EXISTS idx_fact_zone_sales_upload
    ON fact_zone_sales (upload_id)
    WHERE upload_id IS NOT NULL;

-- Silver fact table — 撤单 (order void) event grain.
--
-- New restaurant analytics dimension: 撤单率 / 撤单稽核 (order void rate +
-- staff audit). Greenfield — zero prior void handling in this codebase.
--
-- Source: 二维火 "撤单报表" export — one row per void event (an order that
-- was opened then cancelled before settlement). Columns (verified against
-- a real 青花椒 customer export, 6264 rows): 门店名称/账单号/桌位/开单时间/
-- 撤单时间/撤单时间差(分钟)/撤单原因/操作人. Same preamble-rows-before-header
-- shape as other 二维火 exports (smartbi/ingestion/pos_ingest.py
-- :_detect_header_row already handles this via the 门店名称 marker).
--
-- ⚠️ Idempotency key deviates from the naive "(factory_id, source_type,
-- store_id, bill_no)" pattern used by fact_pos_transaction — see below.
--
-- 账单号 (bill_no) is blank in ~18% of real rows (1113/6264 in the
-- reference export — many voided orders are delivery-platform orders that
-- never got a bill number assigned before cancellation). Two consequences:
--   1. We normalize a blank bill_no to '' (empty string), NOT NULL. Postgres
--      UNIQUE constraints treat every NULL as distinct from every other NULL
--      — a nullable bill_no would silently bypass uniqueness entirely and
--      let a rerun of the loader insert duplicate void rows forever.
--   2. bill_no alone (even as '') is not a reliable natural key at a single
--      store (many blank-bill_no rows share a store). The composite key
--      below — (bill_no, table_no ['桌位', always populated, doubles as
--      channel label for delivery orders], order_open_at, void_at) — was
--      empirically checked against the reference export: 6262 distinct
--      groups out of 6264 rows (only 2 true duplicate pairs). Robust enough
--      for practical rerun-safety without inventing a synthetic id.
CREATE TABLE IF NOT EXISTS fact_pos_void (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(50) NOT NULL,
    upload_id          BIGINT,               -- loose link to upload tracking
    source_type        VARCHAR(20) NOT NULL, -- 'excel' | 'meituan_api' | ...
    store_id           BIGINT NOT NULL,
    bill_no            VARCHAR(100) NOT NULL DEFAULT '', -- '' = not captured by POS export (see note above)
    -- table_no is part of uq_fact_pos_void, so it MUST be NOT NULL: a NULL
    -- never conflicts with anything in a Postgres UNIQUE index, so a blank-桌位
    -- row (rare but possible) would silently duplicate on every rerun. Loader
    -- maps blank -> '' (never None) to match.
    table_no           VARCHAR(50) NOT NULL DEFAULT '', -- 桌位: real table no. OR delivery-channel label (京东外卖/饿了么/...)
    order_open_at      TIMESTAMP NOT NULL,    -- 开单时间
    void_at            TIMESTAMP NOT NULL,    -- 撤单时间
    void_diff_minutes  NUMERIC(10,2),         -- 撤单时间差(分钟)
    void_reason        VARCHAR(200),          -- 撤单原因 (blank in ~65% of rows — honest, not fabricated)
    staff_id           BIGINT,                -- 操作人 (POS operator who voided — same caveat as fact_pos_transaction.staff_id)
    date               DATE NOT NULL,         -- void_at::date — the operational date the void happened
    created_at         TIMESTAMP DEFAULT NOW(),
    updated_at         TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_fact_pos_void UNIQUE (factory_id, source_type, store_id, bill_no, table_no, order_open_at, void_at),
    CONSTRAINT fk_fact_pos_void_store FOREIGN KEY (store_id)
        REFERENCES dim_store (store_id) ON DELETE RESTRICT,
    CONSTRAINT fk_fact_pos_void_staff FOREIGN KEY (staff_id)
        REFERENCES dim_staff (staff_id) ON DELETE SET NULL
);
ALTER TABLE fact_pos_void ENABLE ROW LEVEL SECURITY;
ALTER TABLE fact_pos_void FORCE  ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_pos_void;
CREATE POLICY tenant_isolation ON fact_pos_void FOR ALL
    USING (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));
DROP TRIGGER IF EXISTS trg_fact_pos_void_touch ON fact_pos_void;
CREATE TRIGGER trg_fact_pos_void_touch BEFORE UPDATE ON fact_pos_void
    FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
-- Hot path A: "void rate by store/day" — agg_daily_void materializer source scan.
CREATE INDEX IF NOT EXISTS idx_fact_pos_void_factory_date_store
    ON fact_pos_void (factory_id, date, store_id);
-- Hot path B: staff audit drill-down (skip rows with no attributed operator).
CREATE INDEX IF NOT EXISTS idx_fact_pos_void_factory_staff
    ON fact_pos_void (factory_id, staff_id)
    WHERE staff_id IS NOT NULL;
-- Hot path C: upload rollback ("delete everything from upload 1234").
CREATE INDEX IF NOT EXISTS idx_fact_pos_void_upload
    ON fact_pos_void (upload_id)
    WHERE upload_id IS NOT NULL;

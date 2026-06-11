-- Factory Production Gold ETL schema (Phase 1 of Factory BI).
-- Source: cretas_db.production_batches → smartbi_db Silver + Gold.
--
-- Design:
--   - Silver table:  fact_production_batch  (1 row per completed production batch)
--   - Gold table:    agg_factory_batch_daily (aggregated per factory + stat_date + product_type)
--   - Source grain:  production_batches WHERE status='COMPLETED' AND is_trial IS NOT TRUE
--   - Idempotency:   ON CONFLICT (factory_id, source_pk) DO UPDATE (source_pk = production_batches.id)
--   - RLS:           FORCE ROW LEVEL SECURITY + tenant_isolation policy (matches restaurant gold pattern)
--   - Cost fields:   NUMERIC precision follows Java entity (cost NUMERIC(14,2), unit_cost NUMERIC(12,4),
--                    yield_rate NUMERIC(5,2)) — null means unset in source, not zero
--
-- Naming convention: snake_case table/column names, PK suffix _id for bigserial,
-- source_pk = VARCHAR(191) to hold Java BIGINT id safely as text for join stability.


-- ── fact_production_batch (Silver — 批次级生产事实表) ─────────────────────────
-- Grain: one row per (factory_id, production_batches.id)
-- Updated each ETL run via UPSERT — idempotent by (factory_id, source_pk).
CREATE TABLE IF NOT EXISTS fact_production_batch (
    id                BIGSERIAL PRIMARY KEY,
    factory_id        VARCHAR(50)   NOT NULL,
    source_pk         VARCHAR(191)  NOT NULL,            -- cretas_db.production_batches.id (BIGINT stored as text)
    batch_number      VARCHAR(50),                       -- human-readable batch code
    product_type_id   VARCHAR(100),                      -- cretas_db.product_types.id
    product_name      VARCHAR(100),                      -- denormalised product name (snapshot at ETL time)
    -- Quantities (unit = production_batches.unit at batch level, e.g. kg, 份, 盒)
    planned_qty       NUMERIC(12,2),                     -- production_batches.planned_quantity
    actual_qty        NUMERIC(12,2),                     -- production_batches.actual_quantity
    good_qty          NUMERIC(12,2),                     -- production_batches.good_quantity
    defect_qty        NUMERIC(12,2),                     -- production_batches.defect_quantity
    unit              VARCHAR(20),                       -- kg / 份 / 盒 etc.
    status            VARCHAR(20),                       -- always 'COMPLETED' (filter at source)
    -- Timing
    start_time        TIMESTAMP,
    end_time          TIMESTAMP,
    stat_date         DATE,                              -- DATE(end_time) or DATE(start_time) — for daily agg join key
    -- Labour
    worker_count      INTEGER,
    work_minutes      INTEGER,                           -- production_batches.work_duration_minutes
    -- Cost components (all nullable — null = not yet calculated / not applicable)
    material_cost     NUMERIC(14,2),                     -- 原料成本
    labor_cost        NUMERIC(14,2),                     -- 人工成本
    equipment_cost    NUMERIC(14,2),                     -- 设备成本
    other_cost        NUMERIC(14,2),                     -- 其他成本
    total_cost        NUMERIC(14,2),                     -- 总成本 (sum of above — nullable if all null)
    unit_cost         NUMERIC(12,4),                     -- 单位成本 (may be null for cross-unit batches)
    yield_rate        NUMERIC(5,2),                      -- 良品率 % (good_qty/actual_qty × 100)
    -- Audit
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_fact_prod_batch_factory_source UNIQUE (factory_id, source_pk)
);

ALTER TABLE fact_production_batch ENABLE  ROW LEVEL SECURITY;
ALTER TABLE fact_production_batch FORCE   ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_production_batch;
CREATE POLICY tenant_isolation ON fact_production_batch FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- Hot path: trend queries (factory + date range)
CREATE INDEX IF NOT EXISTS idx_fact_prod_batch_factory_date
    ON fact_production_batch (factory_id, stat_date);
-- Hot path: product-level queries
CREATE INDEX IF NOT EXISTS idx_fact_prod_batch_factory_product
    ON fact_production_batch (factory_id, product_type_id);
-- Hot path: batch lookup by source id
CREATE INDEX IF NOT EXISTS idx_fact_prod_batch_source
    ON fact_production_batch (factory_id, source_pk);

-- Auto-update updated_at on modification (reuse silver_touch_updated_at if defined)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc WHERE proname = 'silver_touch_updated_at'
    ) THEN
        EXECUTE $func$
            DROP TRIGGER IF EXISTS trg_fact_prod_batch_touch ON fact_production_batch;
            CREATE TRIGGER trg_fact_prod_batch_touch
                BEFORE UPDATE ON fact_production_batch
                FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
        $func$;
    END IF;
END;
$$;


-- ── agg_factory_batch_daily (Gold — 批次日级聚合表) ──────────────────────────
-- Grain: one row per (factory_id, stat_date, product_type_id)
-- product_type_id = NULL means the all-products rollup for that day.
-- Updated each ETL run via UPSERT — idempotent by PK.
-- version increments on each upsert so downstream caches can detect staleness.
CREATE TABLE IF NOT EXISTS agg_factory_batch_daily (
    factory_id           VARCHAR(50)   NOT NULL,
    stat_date            DATE          NOT NULL,
    product_type_id      VARCHAR(100),                   -- NULL = all-product day rollup
    -- Batch counts
    batch_count          INTEGER       NOT NULL DEFAULT 0,
    -- Quantity rollups
    total_planned_qty    NUMERIC(18,2),
    total_actual_qty     NUMERIC(18,2),                  -- sum of actual_quantity
    total_good_qty       NUMERIC(18,2),                  -- sum of good_quantity
    -- Yield (weighted average, NULL if no good/actual data)
    avg_yield_rate       NUMERIC(5,2),                   -- AVG(yield_rate) across batches
    -- Cost rollups (all nullable — null means no cost data for this slice)
    total_material_cost  NUMERIC(18,2),
    total_labor_cost     NUMERIC(18,2),
    total_equipment_cost NUMERIC(18,2),
    total_other_cost     NUMERIC(18,2),
    total_cost           NUMERIC(18,2),                  -- sum of total_cost across batches
    -- Gold versioning + freshness
    version              BIGINT        NOT NULL DEFAULT 1,
    updated_at           TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agg_factory_batch_daily PRIMARY KEY (factory_id, stat_date, COALESCE(product_type_id, ''))
);

-- Note: PostgreSQL PRIMARY KEY does not allow nullable columns directly; use a surrogate
-- We use a unique index instead to handle the NULL product_type_id case.
ALTER TABLE agg_factory_batch_daily DROP CONSTRAINT IF EXISTS pk_agg_factory_batch_daily;
ALTER TABLE agg_factory_batch_daily ADD COLUMN IF NOT EXISTS _pk_helper VARCHAR(150)
    GENERATED ALWAYS AS (factory_id || '|' || stat_date::text || '|' || COALESCE(product_type_id, '__ALL__')) STORED;
CREATE UNIQUE INDEX IF NOT EXISTS uq_agg_factory_batch_daily
    ON agg_factory_batch_daily (_pk_helper);

ALTER TABLE agg_factory_batch_daily ENABLE  ROW LEVEL SECURITY;
ALTER TABLE agg_factory_batch_daily FORCE   ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_factory_batch_daily;
CREATE POLICY tenant_isolation ON agg_factory_batch_daily FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- Hot path: time-series trend (factory + date range)
CREATE INDEX IF NOT EXISTS idx_agg_factory_batch_daily_factory_date
    ON agg_factory_batch_daily (factory_id, stat_date);
-- Hot path: product comparison (factory + product + date)
CREATE INDEX IF NOT EXISTS idx_agg_factory_batch_daily_factory_product_date
    ON agg_factory_batch_daily (factory_id, product_type_id, stat_date)
    WHERE product_type_id IS NOT NULL;

-- GRANT DML to smartbi_user so ETL writer can INSERT/UPDATE
GRANT SELECT, INSERT, UPDATE, DELETE ON fact_production_batch   TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_factory_batch_daily TO smartbi_user;
GRANT USAGE ON SEQUENCE fact_production_batch_id_seq            TO smartbi_user;

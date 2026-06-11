-- Process-level production report Silver table (A2 — 工序级成本 Silver).
-- Source: cretas_db.production_reports (每道报工) → smartbi_db Silver.
--
-- Design:
--   - Silver grain:  one row per (factory_id, production_reports.id) — report_type='YIELD'
--   - Idempotency:   ON CONFLICT (factory_id, source_pk) DO UPDATE
--   - RLS:           FORCE ROW LEVEL SECURITY + tenant_isolation policy
--   - Cost honesty:  labor_cost / material_cost stay NULL when source is NULL.
--                    ~90% of reports have no cost data — nulls are NOT coerced to 0.
--   - cost_source:   'reported' when either labor_cost OR material_cost is non-null;
--                    NULL otherwise.  Consumers should filter on cost_source = 'reported'
--                    when they need cost-complete rows only.
--   - yield_rate:    output_quantity / input_quantity × 100 (computed on write, nullable).
--   - Flyway version: V20261004_01 (strictly > V20261003_02, current frontier)
--
-- Naming: snake_case columns, source_pk = VARCHAR(191) for Java BIGINT id safety.

CREATE TABLE IF NOT EXISTS fact_production_report (
    id                   BIGSERIAL     PRIMARY KEY,
    factory_id           VARCHAR(50)   NOT NULL,
    source_pk            VARCHAR(191)  NOT NULL,           -- cretas_db.production_reports.id
    batch_id             VARCHAR(191),                     -- cretas_db.production_batches.id (nullable: some HOURS reports have no batch)
    process_order        INTEGER,                          -- WorkProcessTask.processOrder (冗余, 便于按序排列)
    process_category     VARCHAR(200),                     -- production_reports.process_category
    report_date          DATE,                             -- production_reports.report_date
    report_kind          VARCHAR(10),                      -- INPUT / SEGMENT / OUTPUT / null (旧式)
    -- Quantities (all nullable — honour source nulls)
    input_qty            NUMERIC(12,2),                    -- production_reports.input_quantity
    output_qty           NUMERIC(12,2),                    -- production_reports.output_quantity
    good_qty             NUMERIC(12,2),                    -- production_reports.good_quantity
    yield_rate           NUMERIC(7,4),                     -- output_qty / input_qty * 100, computed, nullable
    -- Labour
    total_work_minutes   INTEGER,                          -- production_reports.total_work_minutes
    total_workers        INTEGER,                          -- production_reports.total_workers
    -- Cost fields (nullable — see design notes above, ~90% NULL in prod)
    labor_cost           NUMERIC(14,2),                    -- production_reports.labor_cost
    material_cost        NUMERIC(14,2),                    -- production_reports.material_cost
    -- Honesty marker: 'reported' if either cost non-null, else NULL
    cost_source          VARCHAR(20),                      -- 'reported' or NULL
    -- Audit
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_fact_prod_report_factory_source UNIQUE (factory_id, source_pk)
);

-- RLS: every query must set app.factory_id GUC in the enclosing transaction
ALTER TABLE fact_production_report ENABLE  ROW LEVEL SECURITY;
ALTER TABLE fact_production_report FORCE   ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fact_production_report;
CREATE POLICY tenant_isolation ON fact_production_report FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- Index: hot path for batch-level rollup (batch_id may be NULL for HOURS reports)
CREATE INDEX IF NOT EXISTS idx_fact_prod_report_factory_batch
    ON fact_production_report (factory_id, batch_id)
    WHERE batch_id IS NOT NULL;

-- Index: hot path for date-range trend
CREATE INDEX IF NOT EXISTS idx_fact_prod_report_factory_date
    ON fact_production_report (factory_id, report_date);

-- Index: cost-complete rows (cost_source queries from analytic endpoints)
CREATE INDEX IF NOT EXISTS idx_fact_prod_report_factory_cost_source
    ON fact_production_report (factory_id, cost_source)
    WHERE cost_source IS NOT NULL;

-- Auto-update updated_at on modification
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc WHERE proname = 'silver_touch_updated_at'
    ) THEN
        EXECUTE $func$
            DROP TRIGGER IF EXISTS trg_fact_prod_report_touch ON fact_production_report;
            CREATE TRIGGER trg_fact_prod_report_touch
                BEFORE UPDATE ON fact_production_report
                FOR EACH ROW EXECUTE FUNCTION silver_touch_updated_at();
        $func$;
    END IF;
END;
$$;

-- GRANT DML to smartbi_user (ETL writer runs as smartbi_user)
GRANT SELECT, INSERT, UPDATE, DELETE ON fact_production_report TO smartbi_user;
GRANT USAGE ON SEQUENCE fact_production_report_id_seq          TO smartbi_user;

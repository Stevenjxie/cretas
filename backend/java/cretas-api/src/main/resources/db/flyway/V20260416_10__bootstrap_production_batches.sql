-- =============================================================================
-- Bootstrap Class 7: production_batches table
--
-- WHY THIS EXISTS
-- Class 3 audits (PR #125 + #126) used grep patterns matching INSERT/UPDATE/
-- DELETE/ALTER/CREATE INDEX/FROM/JOIN — but MISSED `REFERENCES <table>` FK
-- declarations. CI Round 9 revealed V20260516_06__work_process_tasks.sql:53
-- has `REFERENCES production_batches(id)` for a foreign key, but no migration
-- in db/flyway/ ever creates the table. Hibernate ddl-auto=update supplies it
-- on prod/test, but fresh CI DB hits SQL state 42P01.
--
-- Comprehensive FK sweep (after Round 9): `production_batches` is the ONLY
-- table referenced as FK target without bootstrap. 19 other REFERENCES targets
-- (accounts, alert_rules, bom_recipes, customers, factory_warehouses, etc.)
-- all have CREATE TABLE in db/flyway/.
--
-- ENTITY SOURCE
-- entity/ProductionBatch.java — extends BaseEntity, @Table(name=production_batches),
-- BIGSERIAL PK, @Where(deleted_at IS NULL), 35 columns including photo_required
-- / sop_config_id / custom_fields jsonb from later sprint additions.
--
-- Distinct from `production_plans` (entity/ProductionPlan.java, String UUID PK)
-- and `production_reports` (entity/ProductionReport.java, BIGSERIAL, V20260416_07).
-- All three are SEPARATE entities — verified during Class 1 Round 1 audit.
--
-- CONVENTIONS (mirror V20260416_07 / V20260416_08)
-- - CREATE TABLE IF NOT EXISTS — idempotent on prod/test
-- - NO foreign keys (Hibernate adds via ddl-auto post-Flyway)
-- - NO indexes other than PK (later migrations have CREATE INDEX IF NOT EXISTS)
-- - NO triggers
-- - BIGSERIAL for Long @GeneratedValue(IDENTITY) id
-- - BaseEntity audit (created_at / updated_at / deleted_at)
-- - JSONB for custom_fields (@Type JsonBinaryType)
-- - TEXT for photo_completed_stages (entity columnDefinition="JSON" but Java
--   field is String — preserve string-backed Hibernate mapping)
-- =============================================================================

CREATE TABLE IF NOT EXISTS production_batches (
    id                          BIGSERIAL    PRIMARY KEY,
    factory_id                  VARCHAR(50)  NOT NULL,
    batch_number                VARCHAR(50)  NOT NULL,
    production_plan_id          VARCHAR(191),
    product_type_id             VARCHAR(100) NOT NULL,
    product_name                VARCHAR(100),
    planned_quantity            NUMERIC(12,2),
    quantity                    NUMERIC(10,2) NOT NULL,
    unit                        VARCHAR(20)  NOT NULL,
    actual_quantity             NUMERIC(12,2),
    good_quantity               NUMERIC(12,2),
    defect_quantity             NUMERIC(12,2),
    status                      VARCHAR(20)  NOT NULL,
    quality_status              VARCHAR(30),
    start_time                  TIMESTAMP,
    end_time                    TIMESTAMP,
    equipment_id                BIGINT,
    equipment_name              VARCHAR(100),
    supervisor_id               BIGINT,
    supervisor_name             VARCHAR(50),
    worker_count                INTEGER,
    work_duration_minutes       INTEGER,
    material_cost               NUMERIC(12,2),
    labor_cost                  NUMERIC(12,2),
    equipment_cost              NUMERIC(12,2),
    other_cost                  NUMERIC(12,2),
    total_cost                  NUMERIC(12,2),
    unit_cost                   NUMERIC(12,4),
    yield_rate                  NUMERIC(5,2),
    efficiency                  NUMERIC(5,2),
    notes                       TEXT,
    created_by                  BIGINT,
    photo_required              BOOLEAN      DEFAULT FALSE,
    sop_config_id               VARCHAR(50),
    photo_completed_stages      TEXT,
    custom_fields               JSONB        DEFAULT '{}'::jsonb,
    created_at                  TIMESTAMP    DEFAULT NOW(),
    updated_at                  TIMESTAMP    DEFAULT NOW(),
    deleted_at                  TIMESTAMP,
    -- @Column(name="batch_number", unique=true) per entity line 59
    CONSTRAINT uk_production_batches_batch_number UNIQUE (batch_number)
);

-- =============================================================================
-- End of V20260416_10 — Class 7 production_batches bootstrap
-- =============================================================================

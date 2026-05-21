-- =============================================================================
-- Bootstrap completeness Phase 2: Class 3 missing-tables — tables referenced by
-- db/flyway/ migrations but never created within db/flyway (legacy db/migration
-- dir is NOT scanned by Flyway per spring.flyway.locations=classpath:db/flyway).
--
-- WHY THIS EXISTS
-- After PR #121 (V20260416_00 — 14 Hibernate-managed tables + 158 columns) and
-- PR #123 (NULL-tolerance guards on V20260425_02 + _07), CI advanced ~80 Flyway
-- migrations but hit a new failure at V20260430_01:
--   ERROR: relation "smart_bi_sales_data" does not exist (SQL state 42P01)
--
-- Comprehensive audit (per HARD memory rule "3-strike-comprehensive-audit-over-
-- reactive-patching") found this was the tip of an iceberg — 4 missing tables,
-- not 1:
--
-- WHAT THIS DOES
-- Creates 4 missing tables with CREATE TABLE IF NOT EXISTS:
--   - smart_bi_sales_data (referenced by V20260430_01 INSERT, 21 cols)
--   - smart_bi_finance_data (V20260430_01 INSERT + V20260430_02 AR trip rows)
--   - smart_bi_department_data (V20260430_01 INSERT + V20260430_02 dept rows)
--   - production_reports (V20260514_05 CREATE INDEX, pure Hibernate entity)
--
-- Source: 3 smart_bi tables from db/migration/V2026_01_18_01__smart_bi_tables.sql
-- (legacy MySQL-era, NOT in db/flyway), production_reports from
-- entity/ProductionReport.java (no legacy migration — pure Hibernate ddl-auto
-- on prod).
--
-- DESIGN NOTES (mirror V20260416_00 conventions)
-- 1. CREATE TABLE IF NOT EXISTS for idempotency on prod/test (no-op where
--    Hibernate already created the tables).
-- 2. MySQL→PG conversion:
--      - BIGINT PRIMARY KEY → BIGSERIAL PRIMARY KEY
--      - ENUM(...) → VARCHAR(20) (CHECK constraints omitted per design)
--      - INDEX inside CREATE TABLE → omitted (db/flyway later migrations
--        have CREATE INDEX IF NOT EXISTS that runs after)
--      - DECIMAL → NUMERIC
--      - TIMESTAMP WITH TIME ZONE → TIMESTAMP
--      - COMMENT 'x' → omitted (use COMMENT ON COLUMN ... in separate migration
--        if needed)
-- 3. NO foreign keys (Hibernate adds via ddl-auto post-Flyway).
-- 4. NO indexes other than PK (later migrations handle).
-- 5. NO triggers.
-- 6. Minimum columns needed by db/flyway references; Hibernate ddl-auto fills
--    remaining entity columns on prod first-boot.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- smart_bi_sales_data — Excel-parsed sales data for SmartBI module
-- -----------------------------------------------------------------------------
-- Source: db/migration-pg-converted/V2026_01_18_01__smart_bi_tables.sql:108-139
-- Referenced by: V20260430_01 INSERT (21-col F999 test rows)
-- 21 cols total mirror legacy MySQL DDL.
CREATE TABLE IF NOT EXISTS smart_bi_sales_data (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    upload_id           BIGINT,
    order_date          DATE         NOT NULL,
    salesperson_id      VARCHAR(100),
    salesperson_name    VARCHAR(100),
    department          VARCHAR(100),
    region              VARCHAR(100),
    province            VARCHAR(100),
    city                VARCHAR(100),
    customer_name       VARCHAR(200),
    customer_type       VARCHAR(100),
    product_id          VARCHAR(100),
    product_name        VARCHAR(200),
    product_category    VARCHAR(100),
    quantity            NUMERIC(15,4) DEFAULT 0,
    amount              NUMERIC(15,2) DEFAULT 0,
    unit_price          NUMERIC(15,4) DEFAULT 0,
    cost                NUMERIC(15,2) DEFAULT 0,
    profit              NUMERIC(15,2) DEFAULT 0,
    gross_margin        NUMERIC(10,4) DEFAULT 0,
    monthly_target      NUMERIC(15,2) DEFAULT 0,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- smart_bi_finance_data — Excel-parsed financial data (Cost / AR / AP / Budget)
-- -----------------------------------------------------------------------------
-- Source: db/migration-pg-converted/V2026_01_18_01__smart_bi_tables.sql:144-174
-- Referenced by: V20260430_01 INSERT + V20260430_02 (3 AR trip rows for F999)
-- ENUM('COST','AR','AP','BUDGET') → VARCHAR(20); CHECK constraint omitted per
-- design (Hibernate enforces at app layer).
CREATE TABLE IF NOT EXISTS smart_bi_finance_data (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    upload_id           BIGINT,
    record_date         DATE         NOT NULL,
    record_type         VARCHAR(20)  NOT NULL,
    department          VARCHAR(100),
    category            VARCHAR(100),
    customer_name       VARCHAR(200),
    supplier_name       VARCHAR(200),
    material_cost       NUMERIC(15,2) DEFAULT 0,
    labor_cost          NUMERIC(15,2) DEFAULT 0,
    overhead_cost       NUMERIC(15,2) DEFAULT 0,
    total_cost          NUMERIC(15,2) DEFAULT 0,
    receivable_amount   NUMERIC(15,2) DEFAULT 0,
    collection_amount   NUMERIC(15,2) DEFAULT 0,
    aging_days          INTEGER      DEFAULT 0,
    payable_amount      NUMERIC(15,2) DEFAULT 0,
    payment_amount      NUMERIC(15,2) DEFAULT 0,
    budget_amount       NUMERIC(15,2) DEFAULT 0,
    actual_amount       NUMERIC(15,2) DEFAULT 0,
    variance_amount     NUMERIC(15,2) DEFAULT 0,
    due_date            DATE,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- smart_bi_department_data — Department-level performance data
-- -----------------------------------------------------------------------------
-- Source: db/migration-pg-converted/V2026_01_18_01__smart_bi_tables.sql:179-198
-- Referenced by: V20260430_01 INSERT + V20260430_02 (3 department rows for F999)
CREATE TABLE IF NOT EXISTS smart_bi_department_data (
    id                  BIGSERIAL    PRIMARY KEY,
    factory_id          VARCHAR(50)  NOT NULL,
    upload_id           BIGINT,
    record_date         DATE         NOT NULL,
    department          VARCHAR(100) NOT NULL,
    department_id       VARCHAR(100),
    manager_name        VARCHAR(100),
    headcount           INTEGER      DEFAULT 0,
    sales_amount        NUMERIC(15,2) DEFAULT 0,
    sales_target        NUMERIC(15,2) DEFAULT 0,
    cost_amount         NUMERIC(15,2) DEFAULT 0,
    per_capita_sales    NUMERIC(15,2) DEFAULT 0,
    per_capita_cost     NUMERIC(15,2) DEFAULT 0,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- production_reports — Worker production reports (Hibernate-only entity)
-- -----------------------------------------------------------------------------
-- Source: entity/ProductionReport.java (no legacy migration — purely Hibernate
-- ddl-auto on prod). Referenced by V20260514_05 CREATE INDEX on
-- (process_task_id, worker_id, output_quantity, created_at DESC) WHERE deleted_at IS NULL.
-- Columns mirror entity @Column declarations 1:1.
CREATE TABLE IF NOT EXISTS production_reports (
    id                          BIGSERIAL    PRIMARY KEY,
    version                     BIGINT,
    factory_id                  VARCHAR(50)  NOT NULL,
    batch_id                    BIGINT,
    worker_id                   BIGINT       NOT NULL,
    report_type                 VARCHAR(30)  NOT NULL,
    report_mode                 VARCHAR(16)  NOT NULL DEFAULT 'MODE_1',
    schema_id                   VARCHAR(36),
    report_date                 DATE         NOT NULL,
    reporter_name               VARCHAR(100),
    process_category            VARCHAR(200),
    product_name                VARCHAR(200),
    output_quantity             NUMERIC(12,2),
    good_quantity               NUMERIC(12,2),
    defect_quantity             NUMERIC(12,2),
    total_work_minutes          INTEGER,
    total_workers               INTEGER,
    operation_volume            NUMERIC(10,2),
    hour_entries                JSONB,
    non_production_entries      JSONB,
    production_start_time       TIME,
    production_end_time         TIME,
    custom_fields               JSONB,
    photos                      JSONB,
    status                      VARCHAR(20)  DEFAULT 'SUBMITTED',
    rejection_reason            VARCHAR(500),
    synced_to_smartbi           BOOLEAN      DEFAULT FALSE,
    process_task_id             VARCHAR(50),
    is_supplemental             BOOLEAN      DEFAULT FALSE,
    approval_status             VARCHAR(20)  DEFAULT 'PENDING',
    approved_by                 BIGINT,
    approved_at                 TIMESTAMP,
    rejected_reason             VARCHAR(500),
    notes                       VARCHAR(500),
    reversal_of_id              BIGINT,
    created_at                  TIMESTAMP    DEFAULT NOW(),
    updated_at                  TIMESTAMP    DEFAULT NOW(),
    deleted_at                  TIMESTAMP
);

-- =============================================================================
-- End of V20260416_07 bootstrap smartbi and legacy tables.
-- =============================================================================

-- =============================================================================
-- Bootstrap completeness Phase 3: Class 3 missing-tables — stricter sweep
-- after PR #125 (V20260416_07 bootstrapped 4 tables — 3 smart_bi_* + production_reports).
--
-- WHY THIS EXISTS
-- After PR #125 landed, CI advanced further but hit a new failure at V20260430_03:
--   ERROR: relation "process_tasks" does not exist (SQL state 42P01)
--
-- Per HARD memory rule "feedback_pr_review_must_grep_migration_table_names.md":
--   "grep db/flyway/ for all UPDATE|FROM|JOIN table references and verify each
--    target has a CREATE TABLE in db/flyway/ BEFORE the referencing migration"
--
-- A stricter sweep was performed re-using PR #125's methodology but with a
-- comprehensive grep across UPDATE / INSERT INTO / DELETE FROM / ALTER TABLE
-- / CREATE INDEX ON / FROM / JOIN patterns. 172 distinct referenced tables
-- vs 145 created in db/flyway/. Diff produced 29 candidates.
--
-- Triage (28 false-positives, 1 real gap):
--   - 26 candidates were SQL keywords or English words inside comment lines
--     (a, adds, block, both, conflict, desc, did, explicitly, instance, is,
--      keeps, no, of, on, pattern, per, prevention, re, set, sets, statements,
--      status, thresholds, user, username, will).
--   - sales_order_prepayment_records_items (V20260416_05): false positive —
--     ALTER TABLE is guarded inside DO $$ BEGIN IF EXISTS information_schema
--     check, safely skips when table absent.
--   - smart_bi_report_audit_log (V20260513_02): false positive — appears only
--     in a `--` comment line ("RLS on smart_bi_report_audit_log (Phase A)").
--   - process_tasks (V20260430_03): REAL GAP — bare UPDATE statement, no
--     IF EXISTS guard. Source: db/migration/V20260312_04__process_tasks_table.sql
--     (legacy MySQL-era, NOT scanned by Flyway per spring.flyway.locations).
--
-- WHAT THIS DOES
-- Creates 1 missing table with CREATE TABLE IF NOT EXISTS:
--   - process_tasks (referenced by V20260430_03 UPDATE, columns mirror entity
--     ProcessTask.java + legacy V20260312_04 with input_quantity entity-only
--     addition)
--
-- Source: entity/ProcessTask.java + db/migration/V20260312_04__process_tasks_table.sql
-- The legacy migration creates 26 cols; entity adds `input_quantity` (R70 fix).
-- Bootstrap includes all entity columns so Hibernate ddl-auto on prod first-boot
-- finds a complete schema.
--
-- DESIGN NOTES (mirror V20260416_07 conventions)
-- 1. CREATE TABLE IF NOT EXISTS for idempotency on prod/test (no-op where
--    Hibernate already created the table).
-- 2. MySQL→PG conversion:
--      - VARCHAR PK preserved (entity uses String id, not auto-increment)
--      - DECIMAL → NUMERIC
--      - ENUM → VARCHAR(20) (CHECK constraint omitted, entity @Enumerated
--        enforces at app layer)
--      - INDEX inside CREATE TABLE → omitted (db/migration V20260312_04 had
--        3 CREATE INDEX statements after CREATE TABLE; Hibernate @Index on
--        entity will recreate them via ddl-auto)
--      - TIMESTAMP DEFAULT NOW() for audit columns
-- 3. NO foreign keys (Hibernate adds via ddl-auto post-Flyway).
-- 4. NO indexes other than PK (Hibernate @Index on entity handles).
-- 5. NO triggers.
-- 6. Columns mirror entity 1:1 + BaseEntity audit columns (created_at,
--    updated_at, deleted_at) per V20260416_07 production_reports pattern.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- process_tasks — Process production scheduling unit (PROCESS mode tasks)
-- -----------------------------------------------------------------------------
-- Source: entity/ProcessTask.java (27 cols incl. input_quantity R70 addition)
--         db/migration/V20260312_04__process_tasks_table.sql (26 cols, legacy)
-- Referenced by: V20260430_03 UPDATE (R71 clamp completed_quantity overshoot)
-- Entity uses String id (VARCHAR(50)) — NOT auto-increment.
CREATE TABLE IF NOT EXISTS process_tasks (
    id                          VARCHAR(50)   PRIMARY KEY,
    factory_id                  VARCHAR(50)   NOT NULL,
    production_run_id           VARCHAR(50)   NOT NULL,
    product_type_id             VARCHAR(50)   NOT NULL,
    work_process_id             VARCHAR(50)   NOT NULL,
    source_customer_name        VARCHAR(100),
    source_doc_type             VARCHAR(20),
    source_doc_id               VARCHAR(50),
    workflow_version_id         INTEGER,
    planned_quantity            NUMERIC(10,2) NOT NULL,
    completed_quantity          NUMERIC(10,2) DEFAULT 0,
    pending_quantity            NUMERIC(10,2) DEFAULT 0,
    input_quantity              NUMERIC(12,2),
    unit                        VARCHAR(20)   NOT NULL,
    start_date                  DATE,
    expected_end_date           DATE,
    status                      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    previous_terminal_status    VARCHAR(20),
    created_by                  BIGINT        NOT NULL,
    notes                       TEXT,
    version                     BIGINT        DEFAULT 0,
    created_at                  TIMESTAMP     DEFAULT NOW(),
    updated_at                  TIMESTAMP     DEFAULT NOW(),
    deleted_at                  TIMESTAMP
);

-- =============================================================================
-- End of V20260416_08 bootstrap class3 remaining.
-- =============================================================================

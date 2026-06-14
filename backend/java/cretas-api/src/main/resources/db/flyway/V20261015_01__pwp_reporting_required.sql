-- Configurable reporting granularity per product process.
-- product_work_processes is entity-only in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NULL THEN
        RAISE NOTICE 'V20261015_01 skipped: product_work_processes not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE product_work_processes
        ADD COLUMN IF NOT EXISTS reporting_required BOOLEAN NOT NULL DEFAULT TRUE;

    COMMENT ON COLUMN product_work_processes.reporting_required IS
        'Whether this product process requires reporting. false keeps config but skips task spawn/reporting.';
END $$;

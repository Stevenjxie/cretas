-- AUDIT-004: shared auxiliary pot allocation fields for production reports.
-- Guard: production_reports is a Hibernate entity table; fresh CI DB runs Flyway before Hibernate.

DO $$
BEGIN
    IF to_regclass('public.production_reports') IS NULL THEN
        RAISE NOTICE 'V20261026_03 skipped: production_reports not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE production_reports
        ADD COLUMN IF NOT EXISTS aux_pot_no VARCHAR(64),
        ADD COLUMN IF NOT EXISTS aux_pot_total_cost NUMERIC(14,2),
        ADD COLUMN IF NOT EXISTS aux_alloc_method VARCHAR(20);

    COMMENT ON COLUMN production_reports.aux_pot_no IS
        'AUDIT-004 shared auxiliary pot identifier';
    COMMENT ON COLUMN production_reports.aux_pot_total_cost IS
        'AUDIT-004 total auxiliary pot cost allocated to production batches';
    COMMENT ON COLUMN production_reports.aux_alloc_method IS
        'AUDIT-004 allocation method BY_OUTPUT/FIXED_RATIO';
END $$;

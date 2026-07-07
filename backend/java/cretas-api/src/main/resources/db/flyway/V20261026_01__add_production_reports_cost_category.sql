-- CALC-003: explicit cost category for production reports.
-- Guard: production_reports is a Hibernate entity table; fresh CI DB runs Flyway before Hibernate.

DO $$
BEGIN
    IF to_regclass('public.production_reports') IS NULL THEN
        RAISE NOTICE 'V20261026_01 skipped: production_reports not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE production_reports
        ADD COLUMN IF NOT EXISTS cost_category VARCHAR(20);

    COMMENT ON COLUMN production_reports.cost_category IS
        'CALC-003 cost category RAW_MATERIAL/SEASONING/AUXILIARY/PACKAGING/OTHER; null=fallback to legacy step-index heuristic';
END $$;

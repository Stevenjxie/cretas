-- Auxiliary cost configuration for product work processes.
-- Guard: product_work_processes is a Hibernate entity table; fresh CI DB runs Flyway before Hibernate.

DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NULL THEN
        RAISE NOTICE 'V20261027_18 skipped: product_work_processes not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE product_work_processes
        ADD COLUMN IF NOT EXISTS standard_yield_rate NUMERIC(8,4),
        ADD COLUMN IF NOT EXISTS aux_unit_price       NUMERIC(12,4),
        ADD COLUMN IF NOT EXISTS aux_basis            VARCHAR(10);

    COMMENT ON COLUMN product_work_processes.standard_yield_rate IS
        'Standard yield rate for input/output reconciliation, e.g. 0.85 = 85%';
    COMMENT ON COLUMN product_work_processes.aux_unit_price IS
        'Standard auxiliary unit price per kg; null means not configured';
    COMMENT ON COLUMN product_work_processes.aux_basis IS
        'Auxiliary cost basis: INPUT or OUTPUT';
END $$;

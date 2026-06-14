-- SP3 Three-Price Cost Engine: add labor_cost_per_kg to bom_labor_cost_configs.
-- bom_labor_cost_configs is entity-owned in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.bom_labor_cost_configs') IS NULL THEN
        RAISE NOTICE 'V20261010_21 skipped: bom_labor_cost_configs not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE bom_labor_cost_configs
        ADD COLUMN IF NOT EXISTS labor_cost_per_kg DECIMAL(15,4) NULL;

    COMMENT ON COLUMN bom_labor_cost_configs.labor_cost_per_kg IS
        'SP3: Standard labor cost per kg for BOM standard cost calculations. NULL = not configured.';
END $$;

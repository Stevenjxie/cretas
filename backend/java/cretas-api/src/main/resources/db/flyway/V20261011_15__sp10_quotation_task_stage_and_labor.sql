-- SP10: QuotationTask 扩字段 — quote_stage / labor_per_kg / bom_material_cost / mid_quote_id
-- quotation_tasks is entity-owned in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.quotation_tasks') IS NULL THEN
        RAISE NOTICE 'V20261011_15 skipped: quotation_tasks not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE quotation_tasks
        ADD COLUMN IF NOT EXISTS quote_stage       VARCHAR(20)    NOT NULL DEFAULT 'PRE',
        ADD COLUMN IF NOT EXISTS labor_per_kg      NUMERIC(12, 4) NULL,
        ADD COLUMN IF NOT EXISTS bom_material_cost NUMERIC(15, 2) NULL,
        ADD COLUMN IF NOT EXISTS mid_quote_id      VARCHAR(191)   NULL;

    COMMENT ON COLUMN quotation_tasks.quote_stage IS 'PRE=pre quote | MID_PENDING=waiting trial | MID=mid quote calculated | FINAL=final confirmed';
    COMMENT ON COLUMN quotation_tasks.labor_per_kg IS 'R&D estimated labor cost per kg finished goods, independent from BOM';
    COMMENT ON COLUMN quotation_tasks.bom_material_cost IS 'Material cost copied from BomRecipe.totalCost for display';
    COMMENT ON COLUMN quotation_tasks.mid_quote_id IS 'FK to product_mid_quotes.id';
END $$;

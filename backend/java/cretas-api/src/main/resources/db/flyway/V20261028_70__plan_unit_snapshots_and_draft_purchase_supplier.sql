-- Preserve both execution quantity and the original sales-order presentation.
ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS source_display_quantity NUMERIC(20, 6),
    ADD COLUMN IF NOT EXISTS source_display_unit VARCHAR(32),
    ADD COLUMN IF NOT EXISTS workflow_output_unit VARCHAR(32);

UPDATE production_plans
SET workflow_output_unit = planned_unit
WHERE workflow_output_unit IS NULL;

COMMENT ON COLUMN production_plans.planned_unit IS
    'Production base unit for planned_quantity; never overwritten by workflow reporting unit';
COMMENT ON COLUMN production_plans.source_display_quantity IS
    'Original source document quantity for display, such as 10 boxes';
COMMENT ON COLUMN production_plans.source_display_unit IS
    'Original source document unit for display';
COMMENT ON COLUMN production_plans.workflow_output_unit IS
    'Workflow reporting/output unit, separate from planned_unit';

-- Supplier can be selected after automatic draft creation, but is required before submission.
ALTER TABLE purchase_orders
    ALTER COLUMN supplier_id DROP NOT NULL;

COMMENT ON COLUMN purchase_orders.supplier_id IS
    'Nullable only while status is DRAFT; application submission validation requires a supplier';

-- Material requisitions are Hibernate-owned on a fresh database, so Flyway runs
-- before the table exists. Existing databases still need the additive columns
-- and backfill; Hibernate will create both columns from the entity on fresh DBs.
DO $$
BEGIN
    IF to_regclass('public.factory_material_requisitions') IS NULL THEN
        RAISE NOTICE 'V20261028_70 skipped requisition snapshots: factory_material_requisitions not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE factory_material_requisitions
        ADD COLUMN IF NOT EXISTS production_plan_number VARCHAR(64),
        ADD COLUMN IF NOT EXISTS product_name VARCHAR(191);

    UPDATE factory_material_requisitions requisition
    SET production_plan_number = plan.plan_number,
        product_name = product.name
    FROM production_plans plan
    LEFT JOIN product_types product ON product.id = plan.product_type_id
    WHERE requisition.production_plan_id = plan.id
      AND (requisition.production_plan_number IS NULL OR requisition.product_name IS NULL);
END $$;

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS tax_treatment VARCHAR(16) NOT NULL DEFAULT 'TAXABLE',
    ADD COLUMN IF NOT EXISTS tax_exemption_reason VARCHAR(255);

ALTER TABLE raw_material_types
    ADD CONSTRAINT chk_raw_material_tax_treatment
    CHECK (tax_treatment IN ('TAXABLE', 'EXEMPT'));

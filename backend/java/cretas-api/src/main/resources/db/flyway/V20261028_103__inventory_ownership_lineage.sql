-- Closed-loop inventory legal ownership and sales-source snapshots.
-- All columns are nullable and intentionally have no database default/backfill:
-- existing rows remain honest-unknown, while Java defaults cover new ordinary inventory.

ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS ownership VARCHAR(32),
    ADD COLUMN IF NOT EXISTS owner_customer_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_item_id VARCHAR(191);

ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS ownership VARCHAR(32),
    ADD COLUMN IF NOT EXISTS owner_customer_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_item_id VARCHAR(191);

ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS customer_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS processing_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS material_supply_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS output_ownership VARCHAR(32);

ALTER TABLE material_batches DROP CONSTRAINT IF EXISTS chk_material_batches_ownership;
ALTER TABLE material_batches ADD CONSTRAINT chk_material_batches_ownership
    CHECK (ownership IS NULL OR ownership IN ('COMPANY_OWNED', 'CUSTOMER_OWNED'));
ALTER TABLE material_batches DROP CONSTRAINT IF EXISTS chk_material_batches_owner_consistency;
ALTER TABLE material_batches ADD CONSTRAINT chk_material_batches_owner_consistency
    CHECK ((ownership IS NULL AND owner_customer_id IS NULL)
        OR (ownership = 'COMPANY_OWNED' AND owner_customer_id IS NULL)
        OR (ownership = 'CUSTOMER_OWNED' AND owner_customer_id IS NOT NULL));

ALTER TABLE finished_goods_batches DROP CONSTRAINT IF EXISTS chk_finished_goods_batches_ownership;
ALTER TABLE finished_goods_batches ADD CONSTRAINT chk_finished_goods_batches_ownership
    CHECK (ownership IS NULL OR ownership IN ('COMPANY_OWNED', 'CUSTOMER_OWNED'));
ALTER TABLE finished_goods_batches DROP CONSTRAINT IF EXISTS chk_finished_goods_batches_owner_consistency;
ALTER TABLE finished_goods_batches ADD CONSTRAINT chk_finished_goods_batches_owner_consistency
    CHECK ((ownership IS NULL AND owner_customer_id IS NULL)
        OR (ownership = 'COMPANY_OWNED' AND owner_customer_id IS NULL)
        OR (ownership = 'CUSTOMER_OWNED' AND owner_customer_id IS NOT NULL));

ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plans_processing_mode;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plans_processing_mode
    CHECK (processing_mode IS NULL OR processing_mode IN ('STANDARD_SALE', 'TOLL_PROCESSING'));
ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plans_material_supply_mode;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plans_material_supply_mode
    CHECK (material_supply_mode IS NULL OR material_supply_mode IN ('CUSTOMER_SUPPLIED', 'FACTORY_SUPPLIED'));
ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plans_output_ownership;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plans_output_ownership
    CHECK (output_ownership IS NULL OR output_ownership IN ('COMPANY_OWNED', 'CUSTOMER_OWNED'));
ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plans_sales_contract_pair;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plans_sales_contract_pair
    CHECK ((processing_mode IS NULL AND material_supply_mode IS NULL)
        OR (processing_mode IS NOT NULL AND material_supply_mode IS NOT NULL));
ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plans_output_owner_contract;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plans_output_owner_contract
    CHECK (processing_mode IS NULL
        OR (processing_mode = 'STANDARD_SALE' AND output_ownership = 'COMPANY_OWNED')
        OR (processing_mode = 'TOLL_PROCESSING'
            AND output_ownership = 'CUSTOMER_OWNED'
            AND customer_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_material_batches_ownership_owner
    ON material_batches(factory_id, ownership, owner_customer_id);
CREATE INDEX IF NOT EXISTS idx_material_batches_sales_lineage
    ON material_batches(source_sales_order_id, source_sales_order_item_id);
CREATE INDEX IF NOT EXISTS idx_finished_goods_batches_ownership_owner
    ON finished_goods_batches(factory_id, ownership, owner_customer_id);
CREATE INDEX IF NOT EXISTS idx_finished_goods_batches_sales_lineage
    ON finished_goods_batches(source_sales_order_id, source_sales_order_item_id);
CREATE INDEX IF NOT EXISTS idx_production_plans_ownership_source
    ON production_plans(factory_id, output_ownership, customer_id, source_order_id);

COMMENT ON COLUMN material_batches.ownership IS 'COMPANY_OWNED or CUSTOMER_OWNED; NULL only for legacy/unknown lineage';
COMMENT ON COLUMN material_batches.owner_customer_id IS 'Opaque owning customer ID for CUSTOMER_OWNED inventory; no cross-factory FK';
COMMENT ON COLUMN material_batches.source_sales_order_id IS 'Opaque source sales-order snapshot; no cross-factory FK';
COMMENT ON COLUMN material_batches.source_sales_order_item_id IS 'Opaque source sales-order-line snapshot; no cross-factory FK';
COMMENT ON COLUMN finished_goods_batches.ownership IS 'COMPANY_OWNED or CUSTOMER_OWNED; NULL only for legacy/unknown lineage';
COMMENT ON COLUMN finished_goods_batches.owner_customer_id IS 'Opaque owning customer ID for CUSTOMER_OWNED inventory; no cross-factory FK';
COMMENT ON COLUMN finished_goods_batches.source_sales_order_id IS 'Opaque source sales-order snapshot; no cross-factory FK';
COMMENT ON COLUMN finished_goods_batches.source_sales_order_item_id IS 'Opaque source sales-order-line snapshot; no cross-factory FK';
COMMENT ON COLUMN production_plans.customer_id IS 'Customer ID frozen from sales truth when the plan is created/copied';
COMMENT ON COLUMN production_plans.processing_mode IS 'STANDARD_SALE or TOLL_PROCESSING snapshot; NULL for non-sales or legacy contracts';
COMMENT ON COLUMN production_plans.material_supply_mode IS 'CUSTOMER_SUPPLIED or FACTORY_SUPPLIED snapshot; NULL for non-sales or legacy contracts';
COMMENT ON COLUMN production_plans.output_ownership IS 'Legal ownership inherited by production output; NULL only for legacy unknown contracts';

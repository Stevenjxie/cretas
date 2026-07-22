-- Freeze sales supply/ownership semantics on production plans and finished goods.
-- Historical rows deliberately remain NULL/unknown; this migration performs no backfill.

ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS customer_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS processing_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS material_supply_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS output_ownership VARCHAR(32);

ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plan_processing_mode;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plan_processing_mode
    CHECK (processing_mode IS NULL OR processing_mode IN ('STANDARD_SALE', 'TOLL_PROCESSING'));

ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plan_supply_mode;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plan_supply_mode
    CHECK (material_supply_mode IS NULL OR material_supply_mode IN ('CUSTOMER_SUPPLIED', 'FACTORY_SUPPLIED'));

ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plan_output_ownership;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plan_output_ownership
    CHECK (output_ownership IS NULL OR output_ownership IN ('COMPANY_OWNED', 'CUSTOMER_OWNED'));

ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plan_supply_pair;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plan_supply_pair
    CHECK ((processing_mode IS NULL AND material_supply_mode IS NULL)
        OR (processing_mode IS NOT NULL AND material_supply_mode IS NOT NULL));

ALTER TABLE production_plans DROP CONSTRAINT IF EXISTS chk_production_plan_supply_contract;
ALTER TABLE production_plans ADD CONSTRAINT chk_production_plan_supply_contract
    CHECK (
        processing_mode IS NULL
        OR (processing_mode = 'STANDARD_SALE'
            AND material_supply_mode = 'FACTORY_SUPPLIED'
            AND output_ownership = 'COMPANY_OWNED')
        OR (processing_mode = 'TOLL_PROCESSING'
            AND output_ownership = 'CUSTOMER_OWNED'
            AND customer_id IS NOT NULL
            AND source_order_id IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_production_plan_sales_ownership
    ON production_plans(factory_id, customer_id, source_order_id, output_ownership);

COMMENT ON COLUMN production_plans.customer_id IS
    'Opaque customer snapshot inherited from the source sales order';
COMMENT ON COLUMN production_plans.processing_mode IS
    'STANDARD_SALE or TOLL_PROCESSING snapshot; NULL only for legacy/non-sales plans';
COMMENT ON COLUMN production_plans.material_supply_mode IS
    'CUSTOMER_SUPPLIED or FACTORY_SUPPLIED snapshot; NULL only for legacy/non-sales plans';
COMMENT ON COLUMN production_plans.output_ownership IS
    'Legal ownership of future output inventory; NULL only for legacy plans';

ALTER TABLE finished_goods_batches
    ADD COLUMN IF NOT EXISTS ownership VARCHAR(32),
    ADD COLUMN IF NOT EXISTS owner_customer_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_item_id VARCHAR(191);

ALTER TABLE finished_goods_batches DROP CONSTRAINT IF EXISTS chk_finished_goods_ownership;
ALTER TABLE finished_goods_batches ADD CONSTRAINT chk_finished_goods_ownership
    CHECK (ownership IS NULL OR ownership IN ('COMPANY_OWNED', 'CUSTOMER_OWNED'));

ALTER TABLE finished_goods_batches DROP CONSTRAINT IF EXISTS chk_finished_goods_owner_consistency;
ALTER TABLE finished_goods_batches ADD CONSTRAINT chk_finished_goods_owner_consistency
    CHECK ((ownership IS NULL AND owner_customer_id IS NULL)
        OR (ownership = 'COMPANY_OWNED' AND owner_customer_id IS NULL)
        OR (ownership = 'CUSTOMER_OWNED'
            AND owner_customer_id IS NOT NULL
            AND source_sales_order_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_finished_goods_ownership_scope
    ON finished_goods_batches(
        factory_id,
        ownership,
        owner_customer_id,
        source_sales_order_id,
        product_type_id,
        warehouse_id,
        status
    );

COMMENT ON COLUMN finished_goods_batches.ownership IS
    'COMPANY_OWNED or CUSTOMER_OWNED; NULL only for legacy/unknown lineage';
COMMENT ON COLUMN finished_goods_batches.owner_customer_id IS
    'Opaque owning customer ID for CUSTOMER_OWNED finished goods';
COMMENT ON COLUMN finished_goods_batches.source_sales_order_id IS
    'Opaque source sales-order snapshot; no cross-factory FK';
COMMENT ON COLUMN finished_goods_batches.source_sales_order_item_id IS
    'Opaque source sales-order-line snapshot; no cross-factory FK';

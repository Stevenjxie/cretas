-- Customer-owned raw-material inventory lineage.
-- Historical rows remain NULL/unknown; no production data is rewritten.
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS ownership VARCHAR(32),
    ADD COLUMN IF NOT EXISTS owner_customer_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS source_sales_order_item_id VARCHAR(191);

ALTER TABLE material_batches DROP CONSTRAINT IF EXISTS chk_material_batches_ownership;
ALTER TABLE material_batches ADD CONSTRAINT chk_material_batches_ownership
    CHECK (ownership IS NULL OR ownership IN ('COMPANY_OWNED', 'CUSTOMER_OWNED'));
ALTER TABLE material_batches DROP CONSTRAINT IF EXISTS chk_material_batches_owner_consistency;
ALTER TABLE material_batches ADD CONSTRAINT chk_material_batches_owner_consistency
    CHECK ((ownership IS NULL AND owner_customer_id IS NULL)
        OR (ownership = 'COMPANY_OWNED' AND owner_customer_id IS NULL)
        OR (ownership = 'CUSTOMER_OWNED' AND owner_customer_id IS NOT NULL));

CREATE INDEX IF NOT EXISTS idx_material_batches_ownership_owner
    ON material_batches(factory_id, ownership, owner_customer_id);
CREATE INDEX IF NOT EXISTS idx_material_batches_sales_lineage
    ON material_batches(source_sales_order_id, source_sales_order_item_id);

COMMENT ON COLUMN material_batches.ownership IS
    'COMPANY_OWNED or CUSTOMER_OWNED; NULL only for legacy/unknown lineage';
COMMENT ON COLUMN material_batches.owner_customer_id IS
    'Opaque owning customer ID for CUSTOMER_OWNED inventory; no cross-factory FK';
COMMENT ON COLUMN material_batches.source_sales_order_id IS
    'Opaque source sales-order snapshot; no cross-factory FK';
COMMENT ON COLUMN material_batches.source_sales_order_item_id IS
    'Opaque source sales-order-line snapshot; no cross-factory FK';


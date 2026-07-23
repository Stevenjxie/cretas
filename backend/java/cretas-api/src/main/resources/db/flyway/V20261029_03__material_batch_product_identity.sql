-- MaterialBatch carries two distinct inventory identities:
-- RAW inventory -> raw_material_types; WIP/FG inventory -> product_types.
-- Keep both nullable for backwards compatibility, but require at least one identity.
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS product_type_id VARCHAR(191);

CREATE INDEX IF NOT EXISTS idx_batch_product
    ON material_batches (product_type_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_material_batch_product_type'
    ) THEN
        ALTER TABLE material_batches
            ADD CONSTRAINT fk_material_batch_product_type
            FOREIGN KEY (product_type_id) REFERENCES product_types(id);
    END IF;
END $$;

ALTER TABLE material_batches
    DROP CONSTRAINT IF EXISTS chk_material_batch_inventory_identity;

ALTER TABLE material_batches
    ADD CONSTRAINT chk_material_batch_inventory_identity
    CHECK (material_type_id IS NOT NULL OR product_type_id IS NOT NULL)
    NOT VALID;

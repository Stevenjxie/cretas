-- MaterialBatch carries two distinct inventory identities:
-- RAW inventory -> raw_material_types; WIP/FG inventory -> product_types.
-- Keep both columns nullable for backwards compatibility, but every new/updated
-- row must carry exactly one identity. NOT VALID avoids rewriting legacy rows
-- during deployment while still enforcing the rule for subsequent writes.
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS product_type_id VARCHAR(191);

CREATE INDEX IF NOT EXISTS idx_batch_product
    ON material_batches (product_type_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.conname = 'fk_material_batch_product_type'
          AND t.relname = 'material_batches'
          AND n.nspname = current_schema()
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
    CHECK ((material_type_id IS NOT NULL) <> (product_type_id IS NOT NULL))
    NOT VALID;

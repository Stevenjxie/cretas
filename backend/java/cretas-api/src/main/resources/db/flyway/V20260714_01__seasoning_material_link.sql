-- Link versioned seasoning rows to the authoritative raw/auxiliary material catalog.
-- Nullable preserves reads of historical rows created before material selection was required.
-- Fresh databases encounter this backfill before V20261027_12 creates the table.
-- The later create migration owns the complete definition in that case.
DO $$
BEGIN
    IF to_regclass('public.bom_seasoning_items') IS NULL THEN
        RAISE NOTICE 'bom_seasoning_items does not exist yet; deferred to V20261027_12';
        RETURN;
    END IF;

    EXECUTE 'ALTER TABLE bom_seasoning_items '
         || 'ADD COLUMN IF NOT EXISTS material_type_id VARCHAR(191)';

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_bsi_material_type '
         || 'ON bom_seasoning_items (factory_id, material_type_id) '
         || 'WHERE deleted_at IS NULL AND material_type_id IS NOT NULL';

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_bsi_material_type'
    ) THEN
        EXECUTE 'ALTER TABLE bom_seasoning_items '
             || 'ADD CONSTRAINT fk_bsi_material_type '
             || 'FOREIGN KEY (material_type_id) REFERENCES raw_material_types(id) '
             || 'ON DELETE RESTRICT';
    END IF;
END $$;

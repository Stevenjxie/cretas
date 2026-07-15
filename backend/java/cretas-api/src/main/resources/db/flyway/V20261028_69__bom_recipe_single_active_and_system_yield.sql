-- BOM lifecycle invariant:
-- one SKU may have exactly one ACTIVE recipe, and only that row is_current=true.
-- Repair historical rows left as ACTIVE after a newer version was activated.
UPDATE bom_recipes
SET status = 'ARCHIVED',
    updated_at = NOW()
WHERE status = 'ACTIVE'
  AND is_current = FALSE
  AND deleted_at IS NULL;

-- DRAFT rows never occupy the current slot. Historical rows created by the old
-- createRecipe default are released here; activation is the only path to current.
UPDATE bom_recipes
SET is_current = FALSE,
    updated_at = NOW()
WHERE status <> 'ACTIVE'
  AND is_current = TRUE
  AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bom_recipe_one_active_per_product
    ON bom_recipes (factory_id, product_type_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Overall yield is learned from completed production batches. New recipe heads
-- must not receive an artificial 100% value from the database default.
ALTER TABLE bom_recipes
    ALTER COLUMN overall_yield_rate DROP DEFAULT;

-- RAW rows may be relationship-only: no artificial "quantity per finished SKU".
ALTER TABLE bom_items
    ALTER COLUMN standard_quantity DROP NOT NULL;
ALTER TABLE bom_recipe_items
    ALTER COLUMN standard_quantity DROP NOT NULL;
ALTER TABLE bom_recipe_items
    DROP CONSTRAINT IF EXISTS chk_bri_qty;
ALTER TABLE bom_recipe_items
    ADD CONSTRAINT chk_bri_qty CHECK (standard_quantity IS NULL OR standard_quantity > 0);

-- Count-based materials already supported by material/workflow masters must also
-- be valid in BOM recipe rows (for example chicken recorded in 只).
ALTER TABLE bom_recipe_items
    DROP CONSTRAINT IF EXISTS chk_bri_unit;
ALTER TABLE bom_recipe_items
    ADD CONSTRAINT chk_bri_unit
        CHECK (unit IN ('g','kg','mg','ml','L','个','只','件','pcs','袋','箱','瓶','盒','斤'));

-- Separate SKU/BOM output identity from physical net content.
-- No historical backfill: legacy rows remain readable and are corrected only by an explicit draft save.
ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS net_content_quantity NUMERIC(15, 6),
    ADD COLUMN IF NOT EXISTS net_content_unit VARCHAR(20);

ALTER TABLE bom_recipes
    ADD COLUMN IF NOT EXISTS net_content_quantity NUMERIC(15, 6),
    ADD COLUMN IF NOT EXISTS net_content_unit VARCHAR(20);

COMMENT ON COLUMN product_types.net_content_quantity IS
    'Physical net content per one product base unit; paired with net_content_unit';
COMMENT ON COLUMN product_types.net_content_unit IS
    'Canonical physical unit for net content: g/kg/ml/L';
COMMENT ON COLUMN bom_recipes.output_quantity_per_unit IS
    'Output identity quantity per BOM unit; must be 1 in the SKU base unit';
COMMENT ON COLUMN bom_recipes.net_content_quantity IS
    'Pinned SKU net content quantity for this BOM version';
COMMENT ON COLUMN bom_recipes.net_content_unit IS
    'Pinned canonical SKU net content unit for this BOM version';

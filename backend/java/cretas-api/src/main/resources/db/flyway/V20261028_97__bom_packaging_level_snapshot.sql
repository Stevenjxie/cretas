ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS packaging_spec_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS packaging_spec_name_snapshot VARCHAR(100),
    ADD COLUMN IF NOT EXISTS packaging_role VARCHAR(64),
    ADD COLUMN IF NOT EXISTS natural_quantity NUMERIC(15, 6),
    ADD COLUMN IF NOT EXISTS natural_unit VARCHAR(20),
    ADD COLUMN IF NOT EXISTS packaging_package_unit_snapshot VARCHAR(20),
    ADD COLUMN IF NOT EXISTS packaging_base_unit_snapshot VARCHAR(20),
    ADD COLUMN IF NOT EXISTS packaging_conversion_factor_snapshot NUMERIC(20, 8);

CREATE INDEX IF NOT EXISTS idx_bri_packaging_level
    ON bom_recipe_items(factory_id, recipe_id, packaging_spec_id)
    WHERE deleted_at IS NULL AND material_category = 'PACKAGING';

COMMENT ON COLUMN bom_recipe_items.packaging_spec_id IS
    'null=SKU base selling level; non-null=ProductPackagingSpec snapshot identity';
COMMENT ON COLUMN bom_recipe_items.natural_quantity IS
    'Natural quantity at the selected packaging level; standard_quantity remains base-unit equivalent';

-- BOM single source of truth: bom_recipes + bom_recipe_items.
--
-- The existing rows in the legacy table and the three dependent business tables are
-- test data. Product owner explicitly chose deletion instead of migration/backfill.

DELETE FROM factory_material_requisitions;
DELETE FROM bom_change_logs;
DELETE FROM bom_yield_suggestions;

DROP INDEX IF EXISTS idx_bcl_bom;
DROP INDEX IF EXISTS idx_bcl_bom_item;

ALTER TABLE bom_change_logs
    DROP COLUMN IF EXISTS bom_id,
    DROP COLUMN IF EXISTS bom_item_id,
    ADD COLUMN IF NOT EXISTS bom_recipe_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS bom_recipe_item_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_bcl_recipe
    ON bom_change_logs (bom_recipe_id);
CREATE INDEX IF NOT EXISTS idx_bcl_recipe_item
    ON bom_change_logs (bom_recipe_item_id);

ALTER TABLE bom_yield_suggestions
    DROP COLUMN IF EXISTS bom_item_id,
    ADD COLUMN IF NOT EXISTS bom_recipe_id VARCHAR(191);

ALTER TABLE factory_material_requisition_items
    DROP COLUMN IF EXISTS bom_item_id,
    ADD COLUMN IF NOT EXISTS bom_recipe_item_id BIGINT;

ALTER TABLE bom_change_logs
    ADD CONSTRAINT fk_bcl_recipe
        FOREIGN KEY (bom_recipe_id) REFERENCES bom_recipes(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_bcl_recipe_item
        FOREIGN KEY (bom_recipe_item_id) REFERENCES bom_recipe_items(id) ON DELETE SET NULL;

ALTER TABLE bom_yield_suggestions
    ADD CONSTRAINT fk_bys_recipe
        FOREIGN KEY (bom_recipe_id) REFERENCES bom_recipes(id) ON DELETE SET NULL;

ALTER TABLE factory_material_requisition_items
    ADD CONSTRAINT fk_fmri_recipe_item
        FOREIGN KEY (bom_recipe_item_id) REFERENCES bom_recipe_items(id) ON DELETE SET NULL;

DROP TABLE IF EXISTS bom_items;

COMMENT ON COLUMN bom_change_logs.bom_recipe_id IS
    'Recipe header associated with this audit entry';
COMMENT ON COLUMN bom_change_logs.bom_recipe_item_id IS
    'Optional recipe item associated with this audit entry';
COMMENT ON COLUMN bom_yield_suggestions.bom_recipe_id IS
    'Current ACTIVE recipe evaluated by the suggestion';
COMMENT ON COLUMN factory_material_requisition_items.bom_recipe_item_id IS
    'Recipe item snapshot source used to generate the requisition row';

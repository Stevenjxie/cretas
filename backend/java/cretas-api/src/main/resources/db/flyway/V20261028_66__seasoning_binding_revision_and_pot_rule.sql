ALTER TABLE bom_seasoning_items
    ADD COLUMN IF NOT EXISTS subsequent_pot_ratio NUMERIC(8,4);

ALTER TABLE bom_recipes
    ADD COLUMN IF NOT EXISTS seasoning_revision BIGINT NOT NULL DEFAULT 0;

UPDATE bom_seasoning_items bsi
   SET subsequent_pot_ratio = bps.subsequent_pot_ratio
  FROM bom_process_seasoning bps
 WHERE bsi.subsequent_pot_ratio IS NULL
   AND bsi.recipe_id = bps.recipe_id
   AND bsi.work_process_id = bps.work_process_id
   AND bps.deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bsi_recipe_wp_material
    ON bom_seasoning_items(recipe_id, work_process_id, material_type_id)
    WHERE deleted_at IS NULL
      AND work_process_id IS NOT NULL
      AND material_type_id IS NOT NULL;

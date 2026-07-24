-- Workflow ↔ BOM automatic binding, stable DAG slots and multi-output BOM families.
-- Historical rows stay nullable; identities are only derived for new drafts, clones or explicit upgrades.

ALTER TABLE bom_recipes
    ADD COLUMN IF NOT EXISTS bom_family_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS shared_recipe_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS target_terminal_node_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS output_role VARCHAR(24),
    ADD COLUMN IF NOT EXISTS cost_allocation_ratio NUMERIC(7,4);

ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS workflow_material_node_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS workflow_input_port_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS workflow_edge_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS cost_scope VARCHAR(24);

ALTER TABLE bom_seasoning_items
    ADD COLUMN IF NOT EXISTS cost_scope VARCHAR(24);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_br_output_role') THEN
        ALTER TABLE bom_recipes ADD CONSTRAINT ck_br_output_role
            CHECK (output_role IS NULL OR output_role IN ('MAIN', 'CO_PRODUCT', 'BY_PRODUCT'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_br_cost_allocation_ratio') THEN
        ALTER TABLE bom_recipes ADD CONSTRAINT ck_br_cost_allocation_ratio
            CHECK (cost_allocation_ratio IS NULL OR (cost_allocation_ratio > 0 AND cost_allocation_ratio <= 100));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_bri_cost_scope') THEN
        ALTER TABLE bom_recipe_items ADD CONSTRAINT ck_bri_cost_scope
            CHECK (cost_scope IS NULL OR cost_scope IN ('SHARED', 'OUTPUT_EXCLUSIVE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_bsi_cost_scope') THEN
        ALTER TABLE bom_seasoning_items ADD CONSTRAINT ck_bsi_cost_scope
            CHECK (cost_scope IS NULL OR cost_scope IN ('SHARED', 'OUTPUT_EXCLUSIVE'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_br_family_revision
    ON bom_recipes(factory_id, bom_family_id, workflow_revision_id, status)
    WHERE deleted_at IS NULL AND bom_family_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_br_family_terminal
    ON bom_recipes(factory_id, bom_family_id, target_terminal_node_id)
    WHERE deleted_at IS NULL AND bom_family_id IS NOT NULL AND target_terminal_node_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bri_workflow_input_slot
    ON bom_recipe_items(recipe_id, workflow_material_node_id, workflow_input_port_id, workflow_edge_id)
    WHERE deleted_at IS NULL AND workflow_material_node_id IS NOT NULL;

COMMENT ON COLUMN bom_recipes.bom_family_id IS
    'One immutable Workflow revision plus one shared recipe and its terminal Output Recipes';
COMMENT ON COLUMN bom_recipes.shared_recipe_id IS
    'MAIN Output Recipe that owns family-shared raw materials and process auxiliaries';
COMMENT ON COLUMN bom_recipes.target_terminal_node_id IS
    'Stable FINISHED_GOOD Cell selected from the pinned Workflow revision';
COMMENT ON COLUMN bom_recipe_items.workflow_input_port_id IS
    'Stable Workflow input port represented by this BOM material rule';
COMMENT ON COLUMN bom_recipe_items.workflow_edge_id IS
    'Stable Workflow edge from the material Cell to the consuming process input port';

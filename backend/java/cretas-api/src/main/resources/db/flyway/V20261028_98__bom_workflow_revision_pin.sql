-- Immutable Workflow save revisions and exact BOM pinning.
-- Historical rows are intentionally left NULL: this migration must not invent a
-- revision identity or rewrite already-active production configuration.

CREATE TABLE IF NOT EXISTS product_process_workflow_revisions (
    id                    BIGSERIAL PRIMARY KEY,
    factory_id            VARCHAR(64) NOT NULL,
    product_type_id       VARCHAR(64) NOT NULL,
    workflow_id           BIGINT NOT NULL,
    definition_version    INTEGER NOT NULL,
    revision_number       INTEGER NOT NULL,
    revision_hash         VARCHAR(64) NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    schema_version        INTEGER NOT NULL DEFAULT 1,
    nodes_json            JSONB NOT NULL DEFAULT '[]'::jsonb,
    edges_json            JSONB NOT NULL DEFAULT '[]'::jsonb,
    viewport_json         JSONB NOT NULL DEFAULT '{"x":0,"y":0,"zoom":1}'::jsonb,
    process_count         INTEGER NOT NULL DEFAULT 0,
    structurally_complete BOOLEAN NOT NULL DEFAULT FALSE,
    validation_message    VARCHAR(500),
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at            TIMESTAMP NULL,
    CONSTRAINT fk_ppwr_workflow FOREIGN KEY (workflow_id)
        REFERENCES product_process_workflows(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ppwr_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT uk_ppwr_workflow_hash UNIQUE (workflow_id, revision_hash),
    CONSTRAINT uk_ppwr_workflow_revision UNIQUE (workflow_id, revision_number)
);

CREATE INDEX IF NOT EXISTS idx_ppwr_product_saved
    ON product_process_workflow_revisions(factory_id, product_type_id, created_at DESC)
    WHERE deleted_at IS NULL;

ALTER TABLE product_process_workflows
    ADD COLUMN IF NOT EXISTS current_revision_id BIGINT,
    ADD COLUMN IF NOT EXISTS current_revision_hash VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_ppw_current_revision') THEN
        ALTER TABLE product_process_workflows
            ADD CONSTRAINT fk_ppw_current_revision FOREIGN KEY (current_revision_id)
            REFERENCES product_process_workflow_revisions(id) ON DELETE RESTRICT;
    END IF;
END $$;

ALTER TABLE bom_recipes
    ADD COLUMN IF NOT EXISTS workflow_revision_id BIGINT,
    ADD COLUMN IF NOT EXISTS workflow_id BIGINT,
    ADD COLUMN IF NOT EXISTS workflow_definition_version INTEGER,
    ADD COLUMN IF NOT EXISTS workflow_revision_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS workflow_schema_version INTEGER,
    ADD COLUMN IF NOT EXISTS workflow_nodes_snapshot_json JSONB,
    ADD COLUMN IF NOT EXISTS workflow_edges_snapshot_json JSONB;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_br_workflow_revision') THEN
        ALTER TABLE bom_recipes
            ADD CONSTRAINT fk_br_workflow_revision FOREIGN KEY (workflow_revision_id)
            REFERENCES product_process_workflow_revisions(id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_br_workflow_revision
    ON bom_recipes(factory_id, product_type_id, workflow_revision_id)
    WHERE deleted_at IS NULL AND workflow_revision_id IS NOT NULL;

ALTER TABLE bom_seasoning_items
    ADD COLUMN IF NOT EXISTS workflow_process_node_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_bsi_workflow_process_node
    ON bom_seasoning_items(recipe_id, workflow_process_node_id)
    WHERE deleted_at IS NULL AND workflow_process_node_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_bsi_recipe_node_material_section
    ON bom_seasoning_items(recipe_id, workflow_process_node_id, material_type_id, section)
    WHERE deleted_at IS NULL
      AND workflow_process_node_id IS NOT NULL
      AND material_type_id IS NOT NULL;

COMMENT ON TABLE product_process_workflow_revisions IS
    'Immutable content-addressed Workflow save revisions selectable and pinned by BOM drafts';
COMMENT ON COLUMN bom_recipes.workflow_revision_id IS
    'Exact immutable Workflow save revision used to configure and activate this BOM version';
COMMENT ON COLUMN bom_seasoning_items.workflow_process_node_id IS
    'Stable PROCESS Cell identity inside the BOM-pinned Workflow revision';

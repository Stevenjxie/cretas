-- Structured parent -> substitute BOM rules. The legacy free-text
-- bom_recipe_items.substitute_group column remains read-only compatibility data and is not
-- migrated because a group label cannot prove parent identity, ratio, process or package scope.

CREATE TABLE IF NOT EXISTS bom_item_substitutes (
    id                                  VARCHAR(64) PRIMARY KEY,
    factory_id                          VARCHAR(50)  NOT NULL,
    recipe_id                           VARCHAR(191) NOT NULL,
    parent_kind                         VARCHAR(24)  NOT NULL,
    parent_recipe_item_id               BIGINT,
    parent_seasoning_item_id            BIGINT,
    parent_material_type_id_snapshot    VARCHAR(191) NOT NULL,
    parent_material_name_snapshot       VARCHAR(200) NOT NULL,
    material_category_snapshot          VARCHAR(32)  NOT NULL,
    work_process_id_snapshot            VARCHAR(50),
    workflow_process_node_id_snapshot   VARCHAR(128),
    packaging_spec_id_snapshot          VARCHAR(36),
    packaging_role_snapshot             VARCHAR(64),
    substitute_material_type_id         VARCHAR(191) NOT NULL,
    substitute_material_code_snapshot   VARCHAR(50),
    substitute_material_name_snapshot   VARCHAR(200) NOT NULL,
    parent_unit_snapshot                VARCHAR(20) NOT NULL,
    substitute_unit_snapshot            VARCHAR(20) NOT NULL,
    conversion_factor                   NUMERIC(24, 12) NOT NULL,
    conversion_explicit                 BOOLEAN NOT NULL DEFAULT FALSE,
    version                             BIGINT NOT NULL DEFAULT 0,
    created_at                          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at                          TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at                          TIMESTAMP,

    CONSTRAINT fk_bis_recipe FOREIGN KEY (recipe_id)
        REFERENCES bom_recipes(id) ON DELETE CASCADE,
    CONSTRAINT fk_bis_parent_recipe_item FOREIGN KEY (parent_recipe_item_id)
        REFERENCES bom_recipe_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_bis_parent_seasoning_item FOREIGN KEY (parent_seasoning_item_id)
        REFERENCES bom_seasoning_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_bis_substitute_material FOREIGN KEY (substitute_material_type_id)
        REFERENCES raw_material_types(id) ON DELETE RESTRICT,
    CONSTRAINT chk_bis_parent_kind CHECK (parent_kind IN ('RECIPE_ITEM', 'SEASONING_ITEM')),
    CONSTRAINT chk_bis_parent_identity CHECK (
        (parent_kind = 'RECIPE_ITEM'
            AND parent_recipe_item_id IS NOT NULL
            AND parent_seasoning_item_id IS NULL)
        OR
        (parent_kind = 'SEASONING_ITEM'
            AND parent_recipe_item_id IS NULL
            AND parent_seasoning_item_id IS NOT NULL)
    ),
    CONSTRAINT chk_bis_material_category CHECK (
        material_category_snapshot IN ('RAW', 'AUXILIARY', 'PACKAGING')
    ),
    CONSTRAINT chk_bis_parent_scope CHECK (
        (parent_kind = 'SEASONING_ITEM'
            AND material_category_snapshot = 'AUXILIARY'
            AND work_process_id_snapshot IS NOT NULL
            AND workflow_process_node_id_snapshot IS NOT NULL)
        OR
        (parent_kind = 'RECIPE_ITEM'
            AND material_category_snapshot IN ('RAW', 'PACKAGING'))
    ),
    CONSTRAINT chk_bis_packaging_role CHECK (
        material_category_snapshot <> 'PACKAGING' OR packaging_role_snapshot IS NOT NULL
    ),
    CONSTRAINT chk_bis_not_self CHECK (
        parent_material_type_id_snapshot <> substitute_material_type_id
    ),
    CONSTRAINT chk_bis_conversion_factor CHECK (conversion_factor > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_bis_recipe_parent_material
    ON bom_item_substitutes(factory_id, recipe_id, parent_recipe_item_id, substitute_material_type_id)
    WHERE deleted_at IS NULL AND parent_kind = 'RECIPE_ITEM';

CREATE UNIQUE INDEX IF NOT EXISTS uk_bis_seasoning_parent_material
    ON bom_item_substitutes(factory_id, recipe_id, parent_seasoning_item_id, substitute_material_type_id)
    WHERE deleted_at IS NULL AND parent_kind = 'SEASONING_ITEM';

CREATE INDEX IF NOT EXISTS idx_bis_recipe
    ON bom_item_substitutes(factory_id, recipe_id, parent_kind)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_bis_substitute_material
    ON bom_item_substitutes(factory_id, substitute_material_type_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE bom_item_substitutes IS
    'Version-local structured BOM parent-to-substitute rules; no migration from legacy free-text groups.';
COMMENT ON COLUMN bom_item_substitutes.conversion_factor IS
    'Substitute quantity equivalent to one parent requirement quantity; actual consumption remains batch truth.';

-- BOM 统管配方+锅序 (U1) — 把 SP-A 配方折叠进 BOM 子系统 (decision A 真折叠).
-- Spec: docs/superpowers/specs/2026-06-24-bom-recipe-unification-design.md
--
-- 决策锁 (Opus, 基于真实 schema 核对):
--   * 锅序参数 (cooking_pot_base_kg / subsequent_pot_ratio / injection_rate) 加在 bom_recipes (SKU 级),
--     与 product_recipes 同精度 (DECIMAL 12,3 / 8,4 / 8,4).
--   * 调料 ingredient 走新表 bom_seasoning_items, FK 指 bom_recipes.id —— SKU 级,
--     与 bom_recipe_items 同挂法 (原辅料也挂 SKU 级). 版本化由 bom_versions.snapshot_json 承载,
--     不是 version 级独立行 (与现有 BOM 快照机制一致).
--   * FK 类型 = VARCHAR(191) 对齐 bom_recipes.id (计划草稿误写 BIGINT, 已纠正).
--   * 字段镜像 recipe_ingredients (section/seq/name/dosage_per_kg_g/price_source1/2/count_in_seasoning/remark)
--     以保证 U3 迁移 1:1 + U4 成本算法零回归.

ALTER TABLE bom_recipes
    ADD COLUMN IF NOT EXISTS cooking_pot_base_kg  NUMERIC(12,3),
    ADD COLUMN IF NOT EXISTS subsequent_pot_ratio NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS injection_rate       NUMERIC(8,4);

COMMENT ON COLUMN bom_recipes.cooking_pot_base_kg  IS '熟制每锅基准原料(kg), 如 160 (折叠自 product_recipes)';
COMMENT ON COLUMN bom_recipes.subsequent_pot_ratio IS '第二锅起比例, 默认 0.3333, per-SKU 可配 (折叠自 product_recipes)';
COMMENT ON COLUMN bom_recipes.injection_rate       IS '注射率, 如 0.20 (折叠自 product_recipes)';

CREATE TABLE IF NOT EXISTS bom_seasoning_items (
    id                 BIGSERIAL    PRIMARY KEY,
    recipe_id          VARCHAR(191) NOT NULL,          -- FK -> bom_recipes.id (SKU 级)
    factory_id         VARCHAR(50)  NOT NULL,
    section            VARCHAR(20)  NOT NULL,          -- INJECTION | COOKING
    seq                INTEGER      NOT NULL DEFAULT 0, -- 段内排序 (镜像 recipe_ingredients.seq)
    name               VARCHAR(200) NOT NULL,
    dosage_per_kg_g    NUMERIC(14,4) NOT NULL,         -- 每kg原料用量(g)
    price_source1      NUMERIC(14,4),
    price_source2      NUMERIC(14,4),
    count_in_seasoning BOOLEAN      NOT NULL DEFAULT TRUE,  -- 老汤/高汤 = false (不计入调料)
    material_type_id   VARCHAR(191),
    remark             VARCHAR(500),
    created_at         TIMESTAMP    DEFAULT NOW(),
    updated_at         TIMESTAMP    DEFAULT NOW(),
    deleted_at         TIMESTAMP    NULL,
    CONSTRAINT fk_bsi_recipe   FOREIGN KEY (recipe_id) REFERENCES bom_recipes(id),
    CONSTRAINT chk_bsi_section CHECK (section IN ('INJECTION','COOKING'))
);

CREATE INDEX IF NOT EXISTS idx_bsi_recipe  ON bom_seasoning_items (recipe_id, seq)        WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_bsi_factory ON bom_seasoning_items (factory_id, recipe_id) WHERE deleted_at IS NULL;

-- Converge databases where bom_seasoning_items existed before this migration.
ALTER TABLE bom_seasoning_items
    ADD COLUMN IF NOT EXISTS material_type_id VARCHAR(191);

CREATE INDEX IF NOT EXISTS idx_bsi_material_type
    ON bom_seasoning_items (factory_id, material_type_id)
    WHERE deleted_at IS NULL AND material_type_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_bsi_material_type'
    ) THEN
        ALTER TABLE bom_seasoning_items
            ADD CONSTRAINT fk_bsi_material_type
            FOREIGN KEY (material_type_id) REFERENCES raw_material_types(id)
            ON DELETE RESTRICT;
    END IF;
END $$;

COMMENT ON TABLE bom_seasoning_items IS 'BOM 调料 ingredient (注射段+熟制段), 折叠自 recipe_ingredients. SKU 级挂 bom_recipes.id, 版本化由 bom_versions snapshot 承载.';

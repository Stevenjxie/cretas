-- SP1: 嵌套 BOM 成本聚合 — 组合装子产品引用列
-- 当此列非 null 时, BomRecipeServiceImpl.recomputeMaterialCost 会递归查找子产品当前有效 BOM
-- 取 totalCost / outputQuantityPerUnit 作为单位成本, 而非本行 unit_price。
-- "先做后用"场景: 若同时设置 semi_finished_ref_code, 运行时优先从 semi_finished_inventory.unit_cost 取移动均价。

ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS sub_product_type_id VARCHAR(100) NULL;

COMMENT ON COLUMN bom_recipe_items.sub_product_type_id IS
    'SP1: 子产品类型ID (关联 product_types.id)。非 null 时触发嵌套 BOM 成本递归聚合。'
    'null = 普通原材料行 (用 unit_price)。配合 semi_finished_ref_code 支持"先做后用"移动均价优先。';

CREATE INDEX IF NOT EXISTS idx_bri_sub_product_type
    ON bom_recipe_items(factory_id, sub_product_type_id)
    WHERE sub_product_type_id IS NOT NULL;

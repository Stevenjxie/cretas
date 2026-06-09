-- SP8: bom_recipe_items 加 BOM 关联前三位主编码冗余列
-- 不替代 material_type_id UUID 外键; 仅供按类型搜索/统计
ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS primary_code_ref VARCHAR(3);

COMMENT ON COLUMN bom_recipe_items.primary_code_ref IS
    'SP8: 物料前三位主编码冗余列; BOM按类型搜索/统计用; 与 material_type_id 不替代';

CREATE INDEX IF NOT EXISTS idx_bri_primary_code
    ON bom_recipe_items (factory_id, primary_code_ref)
    WHERE primary_code_ref IS NOT NULL;

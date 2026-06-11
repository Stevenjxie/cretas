-- #728 SP12: BomItem (legacy live-edit BOM) 补录 per_portion / semi_finished_ref_code / sub_product_type_id
-- 对应字段已存在于 bom_recipe_items (versioned BOM), 但 bom_items (live 原辅料行) 一直缺列。
-- 前端 /bom/items POST/PUT 提交这三个字段一直被 ORM 静默丢弃 (DTO roundtrip silent drop)。
-- 补全后 BomController.toBomItem 映射生效, 组合装嵌套 BOM + 半成品引用 + 按份投料 可正常持久化。

ALTER TABLE bom_items
    ADD COLUMN IF NOT EXISTS per_portion        BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS semi_finished_ref_code VARCHAR(100) NULL,
    ADD COLUMN IF NOT EXISTS sub_product_type_id    VARCHAR(100) NULL;

COMMENT ON COLUMN bom_items.per_portion IS
    'SP4: true = 按成品份数投料，不随出成率折算（调味料、添加剂等固定用量物料）';
COMMENT ON COLUMN bom_items.semi_finished_ref_code IS
    'SP8: 组合装引用半成品编码，追踪原料→半成品→成品链路';
COMMENT ON COLUMN bom_items.sub_product_type_id IS
    'SP12 #728: 组合装/先做后用子产品类型 ID (关联 product_types.id)；非 null 时触发嵌套 BOM 成本递归聚合';

CREATE INDEX IF NOT EXISTS idx_bom_items_sub_product_type
    ON bom_items(factory_id, sub_product_type_id)
    WHERE sub_product_type_id IS NOT NULL;

-- SP4-T3: BomRecipeItem 添加 per_portion (按份计量标志) + semi_finished_ref_code (半成品引用编码)
-- per_portion: BOOLEAN NOT NULL DEFAULT FALSE — 标记该 BOM 行是否按"份"计量
-- semi_finished_ref_code: VARCHAR(100) NULL — 追踪原料→半成品→成品链路的引用编码

ALTER TABLE bom_recipe_items
    ADD COLUMN IF NOT EXISTS per_portion BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS semi_finished_ref_code VARCHAR(100);

COMMENT ON COLUMN bom_recipe_items.per_portion IS 'SP4: true = 每份用量, false = 按批次总量 (默认)';
COMMENT ON COLUMN bom_recipe_items.semi_finished_ref_code IS 'SP4: 半成品引用编码, 追踪原料→半成品→成品链路';

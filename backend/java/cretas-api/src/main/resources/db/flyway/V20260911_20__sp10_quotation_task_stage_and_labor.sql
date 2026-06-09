-- SP10: QuotationTask 扩字段 — quote_stage / labor_per_kg / bom_material_cost / mid_quote_id
ALTER TABLE quotation_tasks
    ADD COLUMN IF NOT EXISTS quote_stage       VARCHAR(20)    NOT NULL DEFAULT 'PRE',
    ADD COLUMN IF NOT EXISTS labor_per_kg      NUMERIC(12, 4) NULL,
    ADD COLUMN IF NOT EXISTS bom_material_cost NUMERIC(15, 2) NULL,
    ADD COLUMN IF NOT EXISTS mid_quote_id      VARCHAR(191)   NULL;

COMMENT ON COLUMN quotation_tasks.quote_stage IS 'PRE=预报价 | MID_PENDING=等待中试 | MID=中报价已汇算 | FINAL=最终确认';
COMMENT ON COLUMN quotation_tasks.labor_per_kg IS '研发人员经验估算. 单位: 元/kg成品. 与BOM独立';
COMMENT ON COLUMN quotation_tasks.bom_material_cost IS '自动从BomRecipe.totalCost带入, 展示用';
COMMENT ON COLUMN quotation_tasks.mid_quote_id IS 'FK → product_mid_quotes.id, 中报价关联';

-- AUDIT-004: 辅料按锅平摊 — 一锅辅料(卤汤/老汤)被多批共用时, 按各批产出量(或固定比例)分摊到各批.
-- aux_pot_no: 共享锅标识 (同号的报工共用一锅辅料); aux_pot_total_cost: 该锅辅料总成本;
-- aux_alloc_method: BY_OUTPUT(按产出量) / FIXED_RATIO(固定比例). 本批 share = 锅总成本 × 本批产出 ÷ 锅总产出.
-- 全 nullable: 无 aux_pot_no → 辅料按报工 material_cost 原样计 (向后兼容).
ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS aux_pot_no VARCHAR(64);
ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS aux_pot_total_cost NUMERIC(14,2);
ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS aux_alloc_method VARCHAR(20);
COMMENT ON COLUMN production_reports.aux_pot_no IS 'AUDIT-004 共享锅标识 (同号报工共用一锅辅料)';
COMMENT ON COLUMN production_reports.aux_pot_total_cost IS 'AUDIT-004 该锅辅料总成本; 按产出量分摊到各批';
COMMENT ON COLUMN production_reports.aux_alloc_method IS 'AUDIT-004 分摊方式 BY_OUTPUT/FIXED_RATIO';

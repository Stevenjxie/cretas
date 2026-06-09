-- SP1: ProductionReport 新增双产出字段
-- output_kind: FINISHED(仅成品) / SEMI(仅半成品) / BOTH(成品+半成品); NULL=旧式兼容(等同FINISHED)
-- semi_*: 半成品产出量、单位、SKU code
ALTER TABLE production_reports
  ADD COLUMN output_kind          VARCHAR(10)    DEFAULT NULL,
  ADD COLUMN semi_output_quantity NUMERIC(12,2)  DEFAULT NULL,
  ADD COLUMN semi_output_unit     VARCHAR(16)    DEFAULT NULL,
  ADD COLUMN semi_code            VARCHAR(50)    DEFAULT NULL;

COMMENT ON COLUMN production_reports.output_kind          IS 'FINISHED/SEMI/BOTH; NULL=旧式兼容(行为等同FINISHED)';
COMMENT ON COLUMN production_reports.semi_output_quantity IS '半成品产出量; outputKind=SEMI/BOTH时有效';
COMMENT ON COLUMN production_reports.semi_output_unit     IS '半成品产出单位';
COMMENT ON COLUMN production_reports.semi_code            IS '半成品SKU code, 对应SemiFinishedInventory';

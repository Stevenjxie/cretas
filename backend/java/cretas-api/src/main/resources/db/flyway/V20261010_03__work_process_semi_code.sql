-- SP1: WorkProcess 新增半成品产出 SKU code 字段
-- 报工output-options端点从工序配置读取, 减少操作员手选code
ALTER TABLE work_processes
  ADD COLUMN semi_finished_output_code VARCHAR(50) DEFAULT NULL;

COMMENT ON COLUMN work_processes.semi_finished_output_code
  IS 'SP1: 末道或中间道产出半成品时的SKU code; null=仅产成品; 报工output-options端点从此读取';

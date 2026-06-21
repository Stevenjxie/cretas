-- 工序成本配置: 在产品工序定义(product_work_processes)上加默认成本设置, 报工自动继承 →
-- 操作员不手填会计类别/包装明细 (防呆), 生产铺开时一次性配置, 真实报工自动带出.
-- 全 nullable: 无配置 → 报工字段保持原行为 (CALC-003 启发式回退 / 包装不拆 / 辅料不分摊).
ALTER TABLE product_work_processes ADD COLUMN IF NOT EXISTS default_cost_category VARCHAR(20);
ALTER TABLE product_work_processes ADD COLUMN IF NOT EXISTS packaging_template jsonb;
ALTER TABLE product_work_processes ADD COLUMN IF NOT EXISTS aux_alloc_method VARCHAR(20);
COMMENT ON COLUMN product_work_processes.default_cost_category IS '工序默认成本类别(报工继承) RAW_MATERIAL/SEASONING/AUXILIARY/PACKAGING/OTHER';
COMMENT ON COLUMN product_work_processes.packaging_template IS '工序默认包装明细模板 [{name,cost}](报工继承)';
COMMENT ON COLUMN product_work_processes.aux_alloc_method IS '工序辅料分摊方式(报工继承) BY_OUTPUT/FIXED_RATIO';

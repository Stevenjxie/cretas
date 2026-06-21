-- CALC-003: 显式成本类别, 替代 OrderCostBreakdownService 的 step-index 启发式分类 (末道=包装/中间道=调料).
-- 启发式对标准链 OK, 但工序乱序时错位 (e.g. 包装不在最后一道). 显式类别让分类不依赖工序顺序.
-- nullable: 既有报工 null → 回退原启发式 (完全向后兼容); 新报工/seed/配置可显式设值.
-- 取值: RAW_MATERIAL / SEASONING / AUXILIARY / PACKAGING / OTHER (字符串, 与 report_kind/output_kind 同风格).
ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS cost_category VARCHAR(20);
COMMENT ON COLUMN production_reports.cost_category IS
  'CALC-003 成本类别 RAW_MATERIAL/SEASONING/AUXILIARY/PACKAGING/OTHER; null=回退 step-index 启发式';

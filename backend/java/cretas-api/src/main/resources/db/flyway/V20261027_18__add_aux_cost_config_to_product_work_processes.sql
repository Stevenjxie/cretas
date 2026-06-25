-- 段2(B) 辅料标准单价双锚点投料-产出对账 (抓多投/误差)
-- 扩展 product_work_processes (已含 default_cost_category / aux_alloc_method 等成本配置)
-- per (factory × product × work_process)。IF NOT EXISTS 幂等 (镜像 V16 模式)。
ALTER TABLE product_work_processes
    ADD COLUMN IF NOT EXISTS standard_yield_rate NUMERIC(8,4),
    ADD COLUMN IF NOT EXISTS aux_unit_price       NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS aux_basis            VARCHAR(10);

COMMENT ON COLUMN product_work_processes.standard_yield_rate IS
    '标准出成率(配方率, 小数 0.85=85%); 投料-产出对账基准: 实际报工率 vs 标准 → 抓多投/误差。审计②: 对账两端必须一端标准一端实际, 否则恒等0无信号。';
COMMENT ON COLUMN product_work_processes.aux_unit_price IS
    '辅料标准单价(元/kg); 离线按配方算好录入, 非实时BOM; 未配=NULL 视为 0 不崩 (客户: 工序无辅料不能跑不下去)。';
COMMENT ON COLUMN product_work_processes.aux_basis IS
    '元/kg 乘哪侧 kg: INPUT(投入侧)|OUTPUT(产出侧); 保水工序 output>input 必须显式 (审计 B-1)。';

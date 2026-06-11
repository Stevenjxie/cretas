-- V20261017_01__production_plan_skip_process_reporting.sql
--
-- 计划级"免工序报工"开关 (六扇门 Wave2 升级, 取代 #690 的 per-工序属性做法)。
--
-- 需求依据 (Steve 2026-06-10 拍板):
--   报工模型从"per-工序属性"升级为"计划级模式":
--     • 免工序报工 (skip_process_reporting=true): 批次级两点报工 —— 领料报工(入) + 产出报工(出),
--       人工两点不报 (登下一期, 期间级分摊); 成本只含料; 出成率=产出/领料。
--     • 逐道 (skip_process_reporting=false): 现有工序级报工 (#690 reporting_required 仍是逐道里的细粒度选项)。
--
-- 设计:
--   skip_process_reporting 放 production_plans (per-计划维度, 因为同一产品不同计划可选不同模式)。
--   DEFAULT false → 现有所有计划/批次逐道 spawn 行为完全不变 (向后兼容铁律: 现有行回填 false)。
--   只有 createProductionPlan API 层对"新建计划"默认 true (六扇门 want), 旧数据/迁移回填仍 false。
--   工序 optional: 产品可 0 工序; 没配工序时 spawn 强制走两点 (skip 视为 true), 不再 422 阻塞。
--
--   spawn 分支 (WorkProcessTaskServiceImpl.spawnTasks):
--     skip=true / 产品 0 工序 → spawn 2 个批次级哨兵任务 (work_process_id=__MATERIAL_INPUT__ / __FINAL_OUTPUT__,
--       product_work_process_id=0 哨兵, process_order=0 / 9999), 各绑头尾责任人; 不 spawn 工序 task。
--     skip=false → 现有工序 task spawn (#690 reporting_required 过滤不变, 零回归)。
--
--   yield/cost 计算本就 report-driven (按 ProductionReport 按 workProcessTaskId 分组):
--     两点报工 = 1 条 INPUT report (挂 MATERIAL_INPUT task) + 1 条 OUTPUT report (挂 FINAL_OUTPUT task)。
--     calculateBatchYield 的 cumulative = lastOutput / firstInput = 产出/领料 (两点出成率, 无需改算法)。
--     人工不报 → 无 SEGMENT report → laborCost null (诚实, 登下一期独立流程)。
--
-- 幂等: ADD COLUMN IF NOT EXISTS + DEFAULT false; 已有行回填 false (NOT NULL 安全)。

ALTER TABLE production_plans
    ADD COLUMN IF NOT EXISTS skip_process_reporting BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN production_plans.skip_process_reporting IS
    '免工序报工开关 (默认 false 逐道报). true = 批次级两点报工 (领料入+产出出, 人工不报登下一期, 成本只含料, 出成率=产出/领料). 新建计划 API 默认 true; 旧数据/迁移回填 false (向后兼容).';

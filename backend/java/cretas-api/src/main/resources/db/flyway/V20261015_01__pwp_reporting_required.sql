-- V20261015_01__pwp_reporting_required.sql
--
-- 可配置报工粒度 (六扇门 Wave2 核心): 每道工序加 reporting_required 标记。
--
-- 需求依据 (Steve 2026-06-10 拍板):
--   六扇门只需两点报工 —— 领料时报(入) + 出成品/半成品时报(出),
--   中间工序暂不报但保留 (工序配置照常全配, 供其他工厂逐道溯源)。
--   现状"配几道工序就报几道", 六扇门被迫逐道。
--
-- 设计:
--   reporting_required 放 product_work_processes (per-product per-process 配置维度)。
--   DEFAULT true → 现有所有工厂/产品逐道报行为完全不变 (向后兼容铁律)。
--   只有显式设 false 的工序在 spawn 时被跳过 (不生成 work_process_task),
--   该工序的配置行仍保留 (溯源/其他工厂)。
--   yield/cost 计算本就 report-driven (按已存在的 ProductionReport 分组),
--   跳过免报工序的 task → 无该工序报工 → 出成率自然按"领料投入(首道IN)→成品产出(末道OUT)"两点算,
--   无需改 calculateSteps/calculateBatchYield。
--
-- 幂等: ADD COLUMN IF NOT EXISTS + DEFAULT true; 已有行回填 true (NOT NULL 安全)。

ALTER TABLE product_work_processes
    ADD COLUMN IF NOT EXISTS reporting_required BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN product_work_processes.reporting_required IS
    '是否需要报工 (默认 true 逐道报). false = 该工序保留配置但 spawn 时跳过, 不生成报工任务 (六扇门中间免报场景).';

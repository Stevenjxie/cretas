-- Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产→工资 trigger linkno (sourceBatchId)
--
-- 背景: Round 12 §C.7 X3 finding — HJ 生产工序完工 auto-trigger 计件 record → 工资计算.
-- Cretas H-WAGE ship (PayrollRecord + WageCalculationService + PieceRateRule + WorkerDailyEfficiency)
-- 但缺 trigger — submitTeamBatchReport / workerCheckout 完工后 ⚠️ 不创建 WorkerDailyEfficiency.
-- F006 卤制品计件场景痛点.
--
-- 本次 MVP slice:
-- 加 worker_daily_efficiency.source_batch_id 字段 → linkno 反查 production batch 来源.
-- 字段允许 NULL (向后兼容存量数据).
--
-- 后续 Sprint 6: 加 source_batch_id NOT NULL constraint + admin UI 反查.

ALTER TABLE worker_daily_efficiency
    ADD COLUMN IF NOT EXISTS source_batch_id BIGINT NULL;

COMMENT ON COLUMN worker_daily_efficiency.source_batch_id IS
    'Sprint 5 Track E: 来源生产批次 ID (linkno). 通过 ProcessingServiceImpl.submitTeamBatchReport 自动 trigger 写入.';

CREATE INDEX IF NOT EXISTS idx_wde_source_batch
    ON worker_daily_efficiency(source_batch_id)
    WHERE deleted_at IS NULL;

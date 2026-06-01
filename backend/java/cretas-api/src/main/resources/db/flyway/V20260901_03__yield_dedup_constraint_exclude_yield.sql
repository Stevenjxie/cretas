-- V20260901_03__yield_dedup_constraint_exclude_yield.sql
--
-- 报工体系统一 Phase A — C2 修复: 报工防重唯一约束排除 report_type='YIELD' 行。
--
-- Background (真 prod PG 暴露, H2/test 盲区):
--   老 process-mode 报工防重约束 uk_report_worker_batch_date (V2026_03_11_02 加):
--     UNIQUE (factory_id, worker_id, batch_id, report_date) WHERE deleted_at IS NULL AND batch_id IS NOT NULL
--   语义 = "同一工人 + 同一批次 + 同一天 只能报 1 条" (整批报工防重, 对 PROGRESS/HOURS 合理)。
--
--   但 Phase A 的 YIELD 逐道报工本质 = 同一工人 + 同一批次 + 同一天 报 N 道工序
--   (猪舌链 7 道, 一个工人一天跟一批到底)。第 2 道报工就撞 uk_report_worker_batch_date → 409。
--   → 整批出成率只算到第 1 道 (0.9374 而非金标准 0.3828), C1/jsonb/双写全连环失败。
--
--   prod 实测: test_db 无此约束 (假绿 12/12), prod_db 有 (7/12 失败)。Phase A 真设计冲突。
--
-- Fix (Steve 决策: 约束排除 YIELD 行):
--   YIELD 逐道报工不受老 "一人一批一天一条" 约束 (它本就该一天多道);
--   YIELD 自己的防重靠 uq_pr_intermediate_batch_no (任务级批次号, V20260901_01) +
--   ProductionReportRepository.findRecentDuplicate (30s 时间窗, output 维度)。
--   老 PROGRESS/HOURS 报工防重**完全不变**。
--
-- 实现: DROP 旧 uk_report_worker_batch_date, 重建为含 report_type 维度且仅约束非 YIELD 行的 partial index。
--   重建后语义 = "同工人+同批次+同天+同 report_type(非 YIELD) 只 1 条"。
--   - 含 report_type → 同批同天可分别提交 PROGRESS / HOURS (对齐 V2026_03_11_03 的 _no_batch 那条语义)。
--   - WHERE 加 report_type <> 'YIELD' → YIELD 行不进此约束, 逐道多条放行。
--   PostgreSQL: <> 对 NULL report_type 返 NULL (不入索引), 但 report_type 在本表 NOT NULL, 无 NULL 隐患。
--
-- ⚠️ C2b 健壮性修复 (真 test_db 暴露): 旧版直接 CREATE UNIQUE INDEX, 若库里**已有**违反新约束的
--   历史重复行 (同 factory+worker+batch+report_type+report_date 多条非 YIELD), 建索引会失败 →
--   整个 flyway 迁移失败 → 后端无法启动。test_db 有 2 组历史重复 PROGRESS 数据 (F001/worker148/
--   2026-02-24/batch 1&2 各 2 条) 即踩此坑, cretas-backend-test 起不来。prod_db 当时恰好 0 重复才侥幸
--   成功, 但任何库 (含 prod 将来) 一旦有重复就炸。
--   修法: 建索引**前**先软删每组重复中除最新一条 (created_at DESC, id DESC tiebreak) 外的所有行。
--   软删行因 WHERE deleted_at IS NULL 不进索引, 故去重后建索引必成功。0 重复的库 (prod) 此步 no-op。
--
-- 幂等: 去重 UPDATE 用 deleted_at IS NULL 守卫; DROP/CREATE IF EXISTS。prod 本迁移已 success 不重跑;
--   test 重启跑此健壮版必成功。

-- Step 1: 软删违反新约束的历史重复行 (保留每组 created_at 最新的一条)。
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY factory_id, worker_id, batch_id, report_type, report_date
               ORDER BY created_at DESC NULLS LAST, id DESC
           ) AS rn
    FROM production_reports
    WHERE deleted_at IS NULL
      AND batch_id IS NOT NULL
      AND report_type <> 'YIELD'
)
UPDATE production_reports p
SET deleted_at = NOW()
FROM ranked r
WHERE p.id = r.id
  AND r.rn > 1;

-- Step 2: DROP 旧约束 + 建新约束 (含 report_type 且排除 YIELD)。
DROP INDEX IF EXISTS uk_report_worker_batch_date;

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_worker_batch_type_date_non_yield
    ON production_reports (factory_id, worker_id, batch_id, report_type, report_date)
    WHERE deleted_at IS NULL
      AND batch_id IS NOT NULL
      AND report_type <> 'YIELD';

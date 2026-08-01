-- =============================================================================
-- V20261029_46 回滚: 把退回 PENDING 的计划还原成原来的 IN_PROGRESS
--
-- 用法 (手动执行, 不由 Flyway 跑):
--   sudo -u postgres psql -d cretas_prod_db \
--     -f V20261029_46__unstick_started_plans_without_batch_rollback.sql
--
-- ⚠️ 还原的是**台账里的原值**, 不是「凡是 PENDING 的都翻回去」——
--    迁移之后用户可能已经重新点过「开工」(那时会正常建出批次)。对这些计划,
--    现状比台账新, 不该覆盖: 下面用 status='PENDING' 且仍无批次 作为前置条件,
--    只还原「确实还没被重新开工过」的那些行。
--
-- 类型说明: production_plans.id 是 varchar, 台账 plan_id 同为 varchar —— 直接比较,
--    不需要 V20261029_44 回滚脚本里那种 id::varchar 转换 (那条是 bigint 主键)。
-- =============================================================================

\echo '--- 回滚前: 台账 7 行, 其中有多少仍可安全还原 ---'
SELECT count(*) FILTER (WHERE p.status = 'PENDING') AS 可还原,
       count(*) FILTER (WHERE p.status <> 'PENDING') AS 已被重新开工跳过,
       count(*) AS 台账总数
FROM backup_stuck_plans_20260802 b
JOIN production_plans p ON p.id = b.plan_id;

UPDATE production_plans p
SET status     = b.old_status::VARCHAR,
    start_time = b.old_start_time,
    updated_at = NOW()
FROM backup_stuck_plans_20260802 b
WHERE p.id = b.plan_id
  AND p.status = 'PENDING'
  -- 只还原仍然没有批次的: 有批次说明用户已经重新开工成功, 那是比台账更新的事实
  AND NOT EXISTS (SELECT 1 FROM production_batches x
                  WHERE x.production_plan_id = p.id AND x.deleted_at IS NULL);

\echo '--- 回滚后核对 ---'
SELECT p.factory_id, p.plan_number, p.status, p.start_time
FROM backup_stuck_plans_20260802 b
JOIN production_plans p ON p.id = b.plan_id
ORDER BY p.factory_id, p.plan_number;

-- 台账刻意保留 (不 DROP): 留作事故记录, 也让回滚可重复执行。
-- 确认不再需要后手动: DROP TABLE backup_stuck_plans_20260802;

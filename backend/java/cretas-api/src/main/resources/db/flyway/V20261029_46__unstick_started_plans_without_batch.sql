-- =============================================================================
-- V20261029_46: 把「已开工但没有生产批次」的计划退回 PENDING
--
-- 背景
--   startProduction 只翻状态不建批次, 而报工/结单/补建批次三条路都要求批次或 PENDING,
--   于是这些计划永久卡死, 用户只能作废重建。代码侧已在同一个 PR 收敛两个「开工」入口
--   (startProduction 现在复用 createBatchFromPlan), 新计划不会再落进这个状态。
--   这条迁移只处理**存量**。
--
-- 为什么退回 PENDING 而不是补建批次
--   建批次要 spawn 报工任务、校验 workflow pin、映射产线与主管 —— 那是应用层逻辑,
--   SQL 里重写一遍必然与 createBatchFromPlan 漂移(这正是本 bug 的成因: 同一件事两套实现)。
--   退回 PENDING 之后用户重点一次「开工」, 走的就是修好的那条路。
--
-- 为什么写死 id 而不用动态谓词 (V20261029_44 的教训)
--   「IN_PROGRESS 且无批次」是会变的: 部署前只要有人对某个计划补了批次或作废了它,
--   动态谓词就会在部署那一刻命中不同的行。写死 = 干跑看到什么, 部署就改什么。
--   ⚠️ 若部署前状态已变, 台账行数校验会当场 RAISE 阻断, 不会静默改错行。
--
-- 影响面 (2026-08-02 prod cretas_prod_db 实测)
--   7 行: F001 6 个(最早 2026-03-12) + LIUSHANMEN 1 个(2026-08-01)。
--   7 个全部零依赖: material_consumptions=0 / production_settlements=0 /
--   finished_goods_batches=0, 且 plan_source_type 全是 NORMAL
--   (无 SECONDARY ⇒ startProduction 当初没扣过 WIP 半成品, 退回状态不需要回补库存)。
--
-- 回滚
--   scripts/rollback/V20261029_46__rollback.sql (从台账原样还原 status/start_time)
-- =============================================================================

-- 台账: 先把原值存下来, 回滚脚本只认它
CREATE TABLE IF NOT EXISTS backup_stuck_plans_20260802 (
    plan_id        VARCHAR(64) PRIMARY KEY,
    factory_id     VARCHAR(64),
    plan_number    VARCHAR(128),
    old_status     VARCHAR(32),
    old_start_time TIMESTAMP,
    backed_up_at   TIMESTAMP DEFAULT NOW()
);

INSERT INTO backup_stuck_plans_20260802 (plan_id, factory_id, plan_number, old_status, old_start_time)
SELECT p.id, p.factory_id, p.plan_number, p.status::text, p.start_time
FROM production_plans p
WHERE p.id IN (
    'd2e67b69-29fe-40ba-9db0-00ef42a70f99',  -- F001 PLAN-1773114018940-272564FA
    '64026a54-e730-4648-99ef-bd121c2a0077',  -- F001 PLAN-1775056894936-458836EE
    '224512dc-08f0-4129-a2c1-39c00d361694',  -- F001 PLAN-1775057230609-5A1DA6A5
    'fac9045e-445d-46ce-8e71-faeee998c456',  -- F001 PLAN-1777023043578-CA1286DC
    'b8e1be74-1144-4e28-aa6c-ecb207208820',  -- F001 PP-2025-002
    '550e347e-17d6-4865-b123-b5064fbe7766',  -- F001 PP-AUTO-20260403-0076
    '00c32ce5-cc83-4987-967e-c691045668dd'   -- LIUSHANMEN PLAN-1785586079238-5C06ED92
  )
  AND p.deleted_at IS NULL
  AND p.status = 'IN_PROGRESS'
  -- 双保险: 只收「确实没有批次」的行。部署前若有人给某个计划补了批次, 它就不进台账,
  -- 下面的行数校验随即 RAISE, 整条迁移回滚 —— 宁可挡住部署也不改错行。
  AND NOT EXISTS (SELECT 1 FROM production_batches b
                  WHERE b.production_plan_id = p.id AND b.deleted_at IS NULL)
ON CONFLICT (plan_id) DO NOTHING;

-- 行数校验: 干跑时是 7, 部署那一刻必须还是 7, 否则现状已变 → 阻断
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT count(*) INTO n FROM backup_stuck_plans_20260802;
    IF n <> 7 THEN
        RAISE EXCEPTION
            '台账应为 7 行, 实际 % 行 —— 部署前 prod 现状已变(有计划被补了批次/作废/或已被处理)。'
            '请重新取现状、更新本迁移的 id 清单后再部署。', n;
    END IF;
END $$;

-- 退回 PENDING: 状态 + 清空 start_time (让「开工」重新是一个未发生的动作)
UPDATE production_plans p
SET status     = 'PENDING',
    start_time = NULL,
    updated_at = NOW()
FROM backup_stuck_plans_20260802 b
WHERE p.id = b.plan_id;

COMMENT ON TABLE backup_stuck_plans_20260802 IS
    'V20261029_46 台账: 「已开工但无批次」计划退回 PENDING 前的原值, 供回滚使用。';

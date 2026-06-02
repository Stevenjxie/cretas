-- V20260906_04__production_batch_planned_unit.sql
--
-- 报工 P0-2 review fix: production_batches 加 planned_unit 列, 记录 plannedQuantity 的原计划单位。
--
-- Background:
--   末道报工产出单位 (份/盒) ≠ 批次原计划单位 (kg) 时, completeProduction 把 unit 覆盖成产出单位
--   (使 batch/成品入库单位一致), 但 plannedQuantity 仍是原 kg 数。此时
--   efficiency = actualQuantity(份)/plannedQuantity(kg)、unitCost = totalCost(kg基)/goodQuantity(份)
--   跨单位无意义。calculateMetrics() 检测 plannedUnit != unit 时把这两项置 null (诚实留空)。
--   该字段必须持久 — @PreUpdate 每次 save 重算 metrics, 仅瞬态会被完工后无关更新 (如质检改
--   qualityStatus) 用 null 重新算出垃圾值覆盖。
--
-- ⚠️ 表存在守卫 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt): production_batches 是
--   Hibernate JPA entity, 全新 CI DB 上 Flyway 先于 ddl-auto 跑时该表不存在, 裸 ALTER 报
--   "relation does not exist" 阻断启动。to_regclass 守卫: 表存在才 ALTER; 不存在则跳过
--   (Hibernate 随后按 entity 建表+列, entity 已声明 plannedUnit)。
--   prod 该表早已存在 → 守卫无行为改变; ADD COLUMN IF NOT EXISTS 幂等可重跑。
--   validate-on-migrate=false → 编辑已 apply migration 不破 prod checksum 校验。
--   同单位批次此列为 null, 行为零变化。

DO $$
BEGIN
    IF to_regclass('public.production_batches') IS NOT NULL THEN
        ALTER TABLE production_batches
          ADD COLUMN IF NOT EXISTS planned_unit VARCHAR(20);
    END IF;
END $$;

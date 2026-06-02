-- V20260909_02: 半成品WIP 成本字段 — 滚动累计成本 + 单位成本
-- Entity: SemiFinishedInventory.accumulatedCost / unitCost; Spec: docs/superpowers/specs/2026-06-02-f006-yield-p0p1-cost-dedup-design.md §A.2
--
-- 纯字段地基 (单元 A.2), 算法 (成本滚动 / 单位成本计算) 在后续任务做。
-- null = 无成本数据 (诚实, 绝不默认 0); accumulated_cost=滚动累计(人工+材料), unit_cost=accumulated_cost/produced_quantity。
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260909_01 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER (semi_finished_inventory 由 V20260908_01 / Hibernate 建); ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。
DO $$ BEGIN
  IF to_regclass('public.semi_finished_inventory') IS NOT NULL THEN
    ALTER TABLE semi_finished_inventory ADD COLUMN IF NOT EXISTS accumulated_cost NUMERIC(14,2);
    ALTER TABLE semi_finished_inventory ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(14,4);
    COMMENT ON COLUMN semi_finished_inventory.accumulated_cost
        IS '滚动累计成本(人工+材料)(元); null=无成本数据(绝不默认0)';
    COMMENT ON COLUMN semi_finished_inventory.unit_cost
        IS '单位成本 = accumulated_cost / produced_quantity; null=无成本数据';
  END IF;
END $$;

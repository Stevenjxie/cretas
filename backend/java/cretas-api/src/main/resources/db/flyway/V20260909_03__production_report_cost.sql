-- V20260909_03: 逐道报工 成本字段 — 本道人工成本 + 材料成本
-- Entity: ProductionReport.laborCost / materialCost; Spec: docs/superpowers/specs/2026-06-02-f006-yield-p0p1-cost-dedup-design.md §A.3
--
-- 纯字段地基 (单元 A.3), 算法 (人工成本=工时×时薪, 材料成本归集) 在后续任务做。
-- null = 无成本数据 (诚实, 绝不默认 0)。
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260909_01 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER (production_reports 由更早迁移/Hibernate 建); ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。
DO $$ BEGIN
  IF to_regclass('public.production_reports') IS NOT NULL THEN
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS labor_cost NUMERIC(14,2);
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS material_cost NUMERIC(14,2);
    COMMENT ON COLUMN production_reports.labor_cost
        IS '本道人工成本(元); null=无成本数据(绝不默认0)';
    COMMENT ON COLUMN production_reports.material_cost
        IS '本道材料成本(元); null=无成本数据';
  END IF;
END $$;

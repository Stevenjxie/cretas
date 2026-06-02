-- V20260910_02: F006 传统报工适配 — 逐道报工 多段工时/副产物/损耗/留样 字段
-- Entity: ProductionReport.laborSegments/byproducts/wasteQuantity/sampleRetainQuantity
-- Spec: docs/superpowers/specs/2026-06-02-f006-traditional-reporting-adaptation-design.md
-- (photos 列已存在, 不在此迁移; 图片证据复用 photos)
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260909_03 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER; ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。
DO $$ BEGIN
  IF to_regclass('public.production_reports') IS NOT NULL THEN
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS labor_segments jsonb;
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS byproducts jsonb;
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS waste_quantity NUMERIC(14,3);
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS sample_retain_quantity INTEGER;
    COMMENT ON COLUMN production_reports.labor_segments IS '多时段工时 [{startTime,endTime,headcount,note}]; null=用单一workerCount';
    COMMENT ON COLUMN production_reports.byproducts IS '副产物明细 [{name,quantity,unit}] (料头/肥油/骨头)';
    COMMENT ON COLUMN production_reports.waste_quantity IS '损耗量; null=未录';
    COMMENT ON COLUMN production_reports.sample_retain_quantity IS '留样(盒/份, 末道装盒); 入库=产出-留样-剩余';
  END IF;
END $$;

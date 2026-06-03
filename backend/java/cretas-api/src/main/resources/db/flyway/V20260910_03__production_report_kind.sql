-- V20260910_03: F006 三阶段报工 — report_kind 阶段标记
-- Entity: ProductionReport.reportKind (INPUT/SEGMENT/OUTPUT; null=旧式整合报工)
-- Spec: docs/superpowers/specs/2026-06-03-f006-phased-yield-reporting-design.md (单元1)
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260910_02 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER; ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。
DO $$ BEGIN
  IF to_regclass('public.production_reports') IS NOT NULL THEN
    ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS report_kind VARCHAR(10);
    COMMENT ON COLUMN production_reports.report_kind IS '报工阶段 INPUT/SEGMENT/OUTPUT; null=旧式整合报工';
  END IF;
END $$;

-- V20260909_01: WorkProcess 标准时薪(元/小时) — 用于逐道人工成本计算
-- Entity: WorkProcess.standardHourlyRate; Spec: docs/superpowers/specs/2026-06-02-f006-yield-p0p1-cost-dedup-design.md §A.1
--
-- null = 未配置 (诚实, 绝不默认 0); 后续成本计算遇 null 跳过该道人工成本核算。
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260908_02 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER (work_processes 由更早迁移/Hibernate 建); ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。
DO $$ BEGIN
  IF to_regclass('public.work_processes') IS NOT NULL THEN
    ALTER TABLE work_processes ADD COLUMN IF NOT EXISTS standard_hourly_rate NUMERIC(8,2);
  END IF;
END $$;

COMMENT ON COLUMN work_processes.standard_hourly_rate
    IS '标准时薪(元/小时); null=未配置(绝不默认0), 用于逐道人工成本计算';

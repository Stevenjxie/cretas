-- V20260906_03__production_report_worker_columns_guard.sql
--
-- 报工体 P1-3 (张权 G4 "用了多少人 / 一个人一个小时"): production_reports 的
-- total_work_minutes / total_workers 两列采集逐道报工工时/人数, 供 B3 效率指标(产出/工时、产出/人)。
--
-- ⚠️ 防御性幂等迁移 — 这两列实际已由 V20260416_07__bootstrap_smartbi_and_legacy_tables.sql:163-164
--   创建 (CREATE TABLE production_reports), prod/test 早已 apply, 实体 ProductionReport.java:87-91 已声明。
--   本迁移仅防极旧 prod 库 schema drift (缺列): ADD COLUMN IF NOT EXISTS 保证幂等, 已有列时 no-op。
--
-- ⚠️ 表存在守卫 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt): production_reports 在全新 CI DB 上
--   由 Flyway 先建 (V20260416_07), 但为统一安全 pattern 仍用 to_regclass: 表存在才 ALTER, 不存在则跳过
--   (Hibernate 随后按 entity 建表+列)。validate-on-migrate=false → 编辑已 apply migration 不破 prod checksum。

DO $$
BEGIN
    IF to_regclass('public.production_reports') IS NOT NULL THEN
        ALTER TABLE production_reports
          ADD COLUMN IF NOT EXISTS total_work_minutes INTEGER,
          ADD COLUMN IF NOT EXISTS total_workers INTEGER;
    END IF;
END $$;

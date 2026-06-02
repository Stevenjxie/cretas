-- V20260908_02__production_report_source_wip_no.sql
--
-- G7 部分领用地基 — 给 production_reports 加 source_wip_no 列。
--
-- Background:
--   道N+1 报工时引用"领哪个 WIP" (semi_finished_inventory.intermediate_batch_no), 用本列记录。
--   Wave 2 才接入扣减逻辑 (报工保存后扣减源 WIP available_quantity)。本 Wave 仅加列, 不改行为。
--
--   spec: docs/superpowers/specs/2026-06-02-f006-wip-yield-G6G7G8-design.md 第三/四章。
--   entity: com.cretas.aims.entity.ProductionReport.sourceWipNo
--
-- 不挤占现有 jsonb (source_batch_refs / material_batch_refs) — WIP 引用用新字段 source_wip_no。
-- nullable: source_wip_no=null 走旧路径 (向后兼容, 历史批次零回归, 设计章四)。
--
-- ⚠️ 防御性幂等迁移 (per feedback_e2e_pr_gate_freshdb_flyway_startup_debt + V20260906_03 同 pattern):
--   to_regclass 守卫: 表存在才 ALTER (production_reports 由更早迁移建); ADD COLUMN IF NOT EXISTS no-op 已有列。
--   validate-on-migrate=false → 编辑本文件不破 prod checksum。

DO $$
BEGIN
    IF to_regclass('public.production_reports') IS NOT NULL THEN
        ALTER TABLE production_reports
          ADD COLUMN IF NOT EXISTS source_wip_no VARCHAR(64);
    END IF;
END $$;

COMMENT ON COLUMN production_reports.source_wip_no
    IS 'G7 部分领用: 本道领用的 WIP 工序批次号 (semi_finished_inventory.intermediate_batch_no); null 走旧路径(向后兼容)';

-- V20260901_02__workprocess_yield_config.sql
--
-- 报工体系统一 Phase A — work_processes 加出成率配置列。
--   standard_yield_min/max: 标准出成率区间(张权 A7 越界告警,报工时即时校验)
--   needs_input:            该工序是否需录投入量(默认 true;纯包装/检验类工序可设 false)
--   output_unit:            产出单位(处理 kg→盒;为空则沿用现有 unit)
-- spec §3.1 §9.4。现有 unit 列保留(unit=投入单位)。
-- 区间用 NUMERIC(6,4): 支持 0.0001..99.9999,覆盖滚揉保水 1.35 (>1) 与损耗 <1。
--
-- ⚠️ 表存在守卫 (2026-06-01 修 e2e-pr-gate 全新 CI DB): work_processes 是 Hibernate JPA
--   entity (无 Flyway CREATE), 全新 DB 上 Flyway 先于 ddl-auto 跑时该表不存在, 裸 ALTER 报
--   "relation does not exist" 阻断启动。to_regclass 守卫: 表存在才 ALTER; 不存在则跳过
--   (Hibernate 随后按 entity 建表+列, entity 已声明这 4 列)。prod 该表早已存在 → 守卫无
--   行为改变; validate-on-migrate=false → 编辑已 apply migration 不破 prod checksum 校验。

DO $$
BEGIN
    IF to_regclass('public.work_processes') IS NOT NULL THEN
        ALTER TABLE work_processes
          ADD COLUMN IF NOT EXISTS standard_yield_min NUMERIC(6,4),
          ADD COLUMN IF NOT EXISTS standard_yield_max NUMERIC(6,4),
          ADD COLUMN IF NOT EXISTS needs_input        BOOLEAN NOT NULL DEFAULT TRUE,
          ADD COLUMN IF NOT EXISTS output_unit        VARCHAR(20);
    END IF;
END $$;

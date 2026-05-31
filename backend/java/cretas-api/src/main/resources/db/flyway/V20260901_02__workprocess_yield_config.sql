-- V20260901_02__workprocess_yield_config.sql
--
-- 报工体系统一 Phase A — work_processes 加出成率配置列。
--   standard_yield_min/max: 标准出成率区间(张权 A7 越界告警,报工时即时校验)
--   needs_input:            该工序是否需录投入量(默认 true;纯包装/检验类工序可设 false)
--   output_unit:            产出单位(处理 kg→盒;为空则沿用现有 unit)
-- spec §3.1 §9.4。现有 unit 列保留(unit=投入单位)。
-- 区间用 NUMERIC(6,4): 支持 0.0001..99.9999,覆盖滚揉保水 1.35 (>1) 与损耗 <1。

ALTER TABLE work_processes
  ADD COLUMN IF NOT EXISTS standard_yield_min NUMERIC(6,4),
  ADD COLUMN IF NOT EXISTS standard_yield_max NUMERIC(6,4),
  ADD COLUMN IF NOT EXISTS needs_input        BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS output_unit        VARCHAR(20);

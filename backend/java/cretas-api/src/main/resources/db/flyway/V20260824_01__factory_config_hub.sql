-- V20260824_01__factory_config_hub.sql
--
-- Canvas Phase B — Factory Config Hub (工厂配置中心) UI wrap (2026-05-22).
--
-- Background:
--   Canvas Phase B Tab 1 (Factory Config Hub) wraps 6 existing config entities into a
--   tabbed UI under one aggregator controller. The 6 entities cover all factory-scoped
--   tuning knobs:
--     1. FactorySchedulingConfig (factory_scheduling_config)     — 排班权重 / 临时工因子 / 自适应学习
--     2. FactoryTempWorker       (factory_temp_worker)           — 临时工记录 (按 worker)
--     3. HrInsuranceConfig       (hr_insurance_configs)          — 五险一金费率
--     4. WagePolicy              (wage_policy)                   — 工资模式 (PIECE_RATE/HOURLY/MIXED)
--     5. EncodingRule            (encoding_rules)                — 业务单据编号规则
--     6. FactorySettings         (factory_settings)              — 工厂总设置 (AI/通知/工时...)
--
-- Schema changes:
--   Add @Version columns (AUD-4 Lost Update prevention) to all 6 mutable tables.
--   Multiple permission_admin / factory_super_admin can edit concurrently → optimistic
--   locking needed to prevent silent overwrites.
--
-- Pattern: mirrors V20260823_02 (Canvas Phase A Food Safety Hub AUD-4 retrofit) and
--   V20260626_02 (Canvas Phase 2-5 AUD-4 retrofit).
--   - DEFAULT 0 backfills existing rows.
--   - NOT NULL matches Hibernate @Version semantics (null on boxed Long would NPE).
--   - IF NOT EXISTS makes migration idempotent.
--
-- No new index — version column is never queried; Hibernate writes it in the
-- UPDATE ... WHERE id=? AND version=? clause auto-generated for @Version fields.

ALTER TABLE factory_scheduling_config
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE factory_temp_worker
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- hr_insurance_configs: already has `opt_lock_version` from V20260824_06 (P3-batch1 PR #196).
-- Phase B controller uses `getOptLockVersion()` accessor instead of `getVersion()`.
-- No ALTER TABLE here to avoid duplicate AUD-4 columns.

ALTER TABLE wage_policy
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- encoding_rules: already has `opt_lock_version` from V20260824_05 (P3-batch1 PR #196).
-- Phase B controller uses `getOptLockVersion()` accessor instead of `getLockVersion()`.
-- JSON contract still exposes the value under key `lockVersion` for backwards compat.

ALTER TABLE factory_settings
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN factory_scheduling_config.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub. Hibernate auto-increments on save(). Lost Update prevention.';
COMMENT ON COLUMN factory_temp_worker.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub. Hibernate auto-increments on save(). Lost Update prevention.';
COMMENT ON COLUMN wage_policy.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub. Hibernate auto-increments on save(). Lost Update prevention.';
COMMENT ON COLUMN factory_settings.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase B Factory Config Hub. Hibernate auto-increments on save(). Lost Update prevention.';

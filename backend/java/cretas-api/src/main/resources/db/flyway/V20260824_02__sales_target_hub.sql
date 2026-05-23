-- V20260824_02__sales_target_hub.sql
--
-- Canvas Phase B — Sales Target Hub (销售目标中心) UI wrap (2026-05-22).
--
-- Background:
--   Canvas Phase B Tab 2 (Sales Target Hub) wraps the Sprint 7 T5 CommissionRule entity
--   into a tiered-ladder editor UI. Customer requirement (HJ Round 13 §15 业绩 6 项):
--     - 提成阶梯: >10万 → 5% / 10-50万 → 7% / 50万+ → 10%
--     - 周期可选 (monthly/quarterly)
--     - 排行榜按 effective rule × sales × period
--
--   Sprint 7 T5 (PR #30f21faa6 sprint7/T5-sales-target-commission) already shipped the
--   single-rate CommissionRule entity + service. Phase B adds:
--     1. tier_config JSONB column — store tier ladder per rule (or per factory default)
--     2. period_type column — MONTHLY (default) / QUARTERLY
--     3. @Version column — AUD-4 optimistic locking (multiple sales-managers can edit)
--
-- Schema changes:
--   - commission_rules.version (BIGINT) — AUD-4 retrofit
--   - commission_rules.tier_config (JSONB) — optional tier ladder config
--     Example value:
--       [{"minAmount": 0,      "maxAmount": 100000, "rate": 5.0},
--        {"minAmount": 100000, "maxAmount": 500000, "rate": 7.0},
--        {"minAmount": 500000, "maxAmount": null,   "rate": 10.0}]
--     When tier_config IS NULL → use flat `percentage` column (backward compatible).
--   - commission_rules.period_type (VARCHAR) — MONTHLY (default) / QUARTERLY
--   - commission_rules.leaderboard_formula (VARCHAR) — SpEL-style hint for leaderboard ranking
--     Examples: "SUM(amount)", "SUM(amount) * rate", "COUNT(orders)"
--
-- Pattern: mirrors V20260823_02 (Canvas Phase A) for @Version retrofit + extra columns.

ALTER TABLE commission_rules
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE commission_rules
  ADD COLUMN IF NOT EXISTS tier_config JSONB;

ALTER TABLE commission_rules
  ADD COLUMN IF NOT EXISTS period_type VARCHAR(20) NOT NULL DEFAULT 'MONTHLY';

ALTER TABLE commission_rules
  ADD COLUMN IF NOT EXISTS leaderboard_formula VARCHAR(500);

COMMENT ON COLUMN commission_rules.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase B Sales Target Hub. Hibernate auto-increments on save(). Lost Update prevention.';
COMMENT ON COLUMN commission_rules.tier_config IS
  'Optional tier ladder config (JSONB array of {minAmount, maxAmount, rate}). NULL = use flat percentage column. Phase B Sales Target Hub.';
COMMENT ON COLUMN commission_rules.period_type IS
  'Commission period: MONTHLY (default) / QUARTERLY. Used by leaderboard aggregation. Phase B Sales Target Hub.';
COMMENT ON COLUMN commission_rules.leaderboard_formula IS
  'Leaderboard ranking formula hint (e.g. SUM(amount) or SUM(amount) * rate). UI-only display, no SpEL execution server-side. Phase B Sales Target Hub.';

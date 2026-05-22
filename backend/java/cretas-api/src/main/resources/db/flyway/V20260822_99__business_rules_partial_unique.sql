-- V20260822_99__business_rules_partial_unique.sql
--
-- P3-1 follow-up (matrix 2026-05-21, subagent E finding): replace business_rules's
-- regular UNIQUE constraint with a partial unique index that excludes soft-deleted rows.
--
-- Renumber note: originally drafted as V20260626_03 then renamed to V20260822_99 to
-- sit above the latest-applied migration on both DBs (20260821.40), since the deploy
-- profile sets `spring.flyway.out-of-order=false` (application-pg-prod.properties:64).
-- Below-latest migrations are silently skipped — moving above the high-water mark
-- guarantees Flyway picks it up on next restart.
--
-- Background:
--   V20260622_01 created business_rules with:
--     UNIQUE (factory_id, rule_code)
--   Same shape as the original Bug #3 in notify_templates — soft-deleted rows still
--   contest the uniqueness slot, so DELETE-then-recreate with the same ruleCode under
--   the same factoryId raises 23505 even though the live set is empty.
--
-- Subagent E (Canvas-Rules 40-scenario matrix, 2026-05-21) detected this:
--   "S5.1 — Schema unique index on (factory_id, rule_code) includes soft-deleted rows;
--    recreating a previously-deleted ruleCode returns 409. Differs from sister-Bug3
--    partial-unique-index pattern. Either intended or needs migration to add
--    WHERE deleted_at IS NULL."
--
-- This migration aligns business_rules with the same pattern proven for notify_templates
-- (V20260622_99) and scheduled_tasks (V20260624_01). Postgres auto-generates the
-- backing index name for the original UNIQUE constraint, so the DROP CONSTRAINT path
-- handles cleanup without hardcoding a name.
--
-- Effect after apply:
--   - Soft-deleted (deleted_at IS NOT NULL) rows excluded from uniqueness check.
--   - A live (deleted_at IS NULL) row with the same (factory_id, rule_code) still
--     conflicts → 409 DUPLICATE with the existing actionHint.
--   - DELETE+recreate of the same ruleCode now succeeds with a new UUID.
--
-- Idempotency:
--   DROP CONSTRAINT IF EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS guards let this
--   re-run safely (per server-operations.md escape hatch + Apr 28 incident learnings).

BEGIN;

-- The constraint name auto-assigned by PostgreSQL for an inline `UNIQUE(a, b)` is
-- `<table>_<col1>_<col2>_key` by default. Try both possible names defensively.
ALTER TABLE business_rules
    DROP CONSTRAINT IF EXISTS business_rules_factory_id_rule_code_key;

-- Some environments may have renamed the constraint manually; also handle a generic name.
ALTER TABLE business_rules
    DROP CONSTRAINT IF EXISTS uq_business_rules_factory_code;

CREATE UNIQUE INDEX IF NOT EXISTS uq_business_rules_factory_code_active
    ON business_rules (factory_id, rule_code)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX uq_business_rules_factory_code_active IS
    'P3-1 follow-up (matrix 2026-05-21): partial unique index — excludes soft-deleted rows so DELETE+recreate works. Replaces UNIQUE(factory_id, rule_code) from V20260622_01.';

COMMIT;

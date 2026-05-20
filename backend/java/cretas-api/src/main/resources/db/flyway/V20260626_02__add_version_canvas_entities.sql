-- ============================================================================
-- AUD-4 P1 Lost Update prevention (edge audit 2026-05-20):
-- Add JPA @Version optimistic locking column to 5 Canvas entities.
--
-- Background: 2 parallel PUT to the same Canvas row (e.g. two factory admins
-- both editing the same PricingStrategy) both succeed and last writer wins
-- silently. With @Version, Hibernate detects the stale snapshot on the loser
-- and throws ObjectOptimisticLockingFailureException. GlobalExceptionHandler
-- already maps that to HTTP 409 (with this PR enriched: + actionHint
-- "请刷新页面查看最新数据后再编辑" + severity warning) so the user can
-- refresh and retry instead of silently overwriting.
--
-- Affected entities (5 Canvas Phase 2-5):
--   - pricing_strategies        (PricingStrategy,   PUT /pricing/strategies/{id})
--   - alert_rules               (AlertRule,         PUT/PATCH /alerts/rules/{id})
--   - business_rules            (BusinessRule,      PUT /canvas-rules/{id})
--   - notify_templates          (NotifyTemplate,    PUT /notify/templates/{id})  <-- stub now
--   - scheduled_tasks           (ScheduledTask,     PUT /scheduled-tasks/{id})
--
-- Column shape: BIGINT NOT NULL DEFAULT 0
--   - DEFAULT 0 backfills existing rows to a known starting version.
--   - NOT NULL matches Hibernate's expected @Version semantics
--     (any null read would NPE on the boxed Long).
--   - IF NOT EXISTS makes the migration idempotent in case prod was already
--     manually patched (per server-operations.md escape hatch).
--
-- No new index — the column is never queried; Hibernate writes it in the
-- UPDATE ... WHERE id=? AND version=? clause that the framework adds
-- automatically when @Version is present.
-- ============================================================================

ALTER TABLE pricing_strategies
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE alert_rules
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE business_rules
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE notify_templates
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE scheduled_tasks
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN pricing_strategies.version IS 'AUD-4 JPA @Version optimistic lock — Hibernate auto-increments on each save(). Lost Update prevention.';
COMMENT ON COLUMN alert_rules.version        IS 'AUD-4 JPA @Version optimistic lock — Hibernate auto-increments on each save(). Lost Update prevention.';
COMMENT ON COLUMN business_rules.version     IS 'AUD-4 JPA @Version optimistic lock — Hibernate auto-increments on each save(). Lost Update prevention.';
COMMENT ON COLUMN notify_templates.version   IS 'AUD-4 JPA @Version optimistic lock — Hibernate auto-increments on each save(). Lost Update prevention.';
COMMENT ON COLUMN scheduled_tasks.version    IS 'AUD-4 JPA @Version optimistic lock — Hibernate auto-increments on each save(). Lost Update prevention.';

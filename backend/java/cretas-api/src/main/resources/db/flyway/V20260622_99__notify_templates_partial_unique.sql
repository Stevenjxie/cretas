-- V20260521_51__notify_templates_partial_unique.sql
--
-- Bug #3 fix (matrix 2026-05-21 P1): replace the regular UNIQUE constraint with a
-- partial unique index that excludes soft-deleted rows.
--
-- Background:
--   V20260621_01 created the original `notify_templates` table with
--     CONSTRAINT uq_notify_templates_factory_code UNIQUE (factory_id, template_code)
--   This is a *regular* uniqueness constraint, so soft-deleted rows still occupy
--   the slot. DELETE-then-recreate with the same templateCode under the same factoryId
--   raised 23505 "duplicate key" at INSERT time — surfaced as 409 in
--   NotifyTemplateController.create when in fact the live (non-deleted) set was empty.
--
-- Sister migration V20260624_01 used a partial unique index for `scheduled_tasks`
-- (lines 28-30 in ScheduledTask.java reference it), so the pattern is already
-- proven in the codebase. This migration aligns notify_templates with that pattern.
--
-- Effect after apply:
--   - Soft-deleted (deleted_at IS NOT NULL) rows are excluded from the uniqueness check.
--   - A live (deleted_at IS NULL) row with the same (factory_id, template_code) still
--     conflicts → 409 with hint "请换一个唯一的 templateCode, 或编辑现有模板".
--   - Recreating after a soft-delete now succeeds, since the deleted row no longer
--     contests the uniqueness slot.
--
-- Idempotency:
--   IF NOT EXISTS / IF EXISTS guards let this re-run on environments where it was
--   manually applied earlier without tracker entry (per server-operations.md escape
--   hatch + Apr 28 incident).

BEGIN;

ALTER TABLE notify_templates
    DROP CONSTRAINT IF EXISTS uq_notify_templates_factory_code;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notify_templates_factory_code_active
    ON notify_templates (factory_id, template_code)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX uq_notify_templates_factory_code_active IS
    'Bug #3 (matrix 2026-05-21): partial unique index — excludes soft-deleted rows so DELETE+recreate works. Replaces uq_notify_templates_factory_code from V20260621_01.';

COMMIT;

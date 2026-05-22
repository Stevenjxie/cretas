-- V20260823_02__food_safety_hub.sql
--
-- Canvas Phase A — Food Safety Hub (食品安全中心) UI wrap (2026-05-21).
--
-- Background:
--   Canvas Phase A Tab 2 (Food Safety Hub) wraps Sprint 8 P3 Phase A entities
--   (HaccpCheckpoint / HaccpMonitoringRecord / AdditiveLimit / RecallEvent /
--   RecallAction) into a tabbed UI. New endpoints (CanvasFoodSafetyController)
--   expose CRUD with AUD-4 optimistic locking + safe-commit pattern.
--
-- Schema changes:
--   1. Add @Version columns (AUD-4 Lost Update prevention) to 2 mutable entities:
--      - haccp_checkpoints  (full CRUD via UI — multiple food-safety admins can edit)
--      - recall_events      (status transition workflow — high concurrency multi-role)
--
--   HaccpMonitoringRecord / RecallAction are log-write-once (no PUT endpoint), so
--   no @Version needed. AdditiveLimit is system-level read-only (GB 2760 seed data,
--   no factory PUT path), so no @Version needed.
--
-- Pattern: mirrors V20260626_02 (Canvas Phase 2-5 AUD-4 retrofit).
--   - DEFAULT 0 backfills existing rows.
--   - NOT NULL matches Hibernate @Version semantics (null on boxed Long would NPE).
--   - IF NOT EXISTS makes migration idempotent (per server-operations.md escape hatch).
--
-- No new index — version column is never queried; Hibernate writes it in the
-- UPDATE ... WHERE id=? AND version=? clause auto-generated for @Version fields.

ALTER TABLE haccp_checkpoints
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE recall_events
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN haccp_checkpoints.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase A Food Safety Hub. Hibernate auto-increments on save(). Lost Update prevention.';
COMMENT ON COLUMN recall_events.version IS
  'AUD-4 JPA @Version optimistic lock — Canvas Phase A Food Safety Hub. Hibernate auto-increments on save(). Lost Update prevention.';

-- Phase 3 Canvas-Notify Step T8 — notify_logs table
-- Spec: docs/superpowers/specs/2026-05-18-canvas-notify-phase3-spec.md §3.2
-- @since 2026-05-18 (Phase 3 skeleton)

CREATE TABLE IF NOT EXISTS notify_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  template_code VARCHAR(100),
  recipient_user_id BIGINT,
  channel VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_msg TEXT,
  sent_at TIMESTAMP DEFAULT NOW(),
  created_at TIMESTAMP DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMP DEFAULT NOW() NOT NULL,
  deleted_at TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_notify_logs_factory_sent
  ON notify_logs (factory_id, sent_at DESC)
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notify_logs_recipient
  ON notify_logs (recipient_user_id, channel)
  WHERE deleted_at IS NULL;

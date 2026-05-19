-- Phase 3 Canvas-Notify Step T8 — notify_templates table
-- Spec: docs/superpowers/specs/2026-05-18-canvas-notify-phase3-spec.md §3.1
-- @since 2026-05-18 (Phase 3 skeleton)

CREATE TABLE IF NOT EXISTS notify_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  template_code VARCHAR(100) NOT NULL,
  title VARCHAR(255),
  body_template TEXT,
  channels JSONB DEFAULT '[]'::jsonb,
  variables_schema_json JSONB DEFAULT '{}'::jsonb,
  created_at TIMESTAMP DEFAULT NOW() NOT NULL,
  updated_at TIMESTAMP DEFAULT NOW() NOT NULL,
  deleted_at TIMESTAMP NULL,
  CONSTRAINT uq_notify_templates_factory_code UNIQUE (factory_id, template_code)
);

CREATE INDEX IF NOT EXISTS idx_notify_templates_factory
  ON notify_templates (factory_id)
  WHERE deleted_at IS NULL;

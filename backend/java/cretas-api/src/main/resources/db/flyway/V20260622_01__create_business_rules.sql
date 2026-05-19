-- Canvas-Rules Phase 4a — business_rules table
-- Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §3
-- Created: 2026-05-18

CREATE TABLE business_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  rule_code VARCHAR(100) NOT NULL,
  rule_name VARCHAR(255),
  scope VARCHAR(20) NOT NULL,
  condition_spel TEXT,
  action_type VARCHAR(30) NOT NULL,
  action_config_json JSONB DEFAULT '{}'::jsonb,
  priority INT NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL,
  UNIQUE(factory_id, rule_code)
);

CREATE INDEX idx_business_rules_factory_scope
  ON business_rules (factory_id, scope, enabled, priority)
  WHERE deleted_at IS NULL;

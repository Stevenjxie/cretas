-- Canvas-Rules Phase 4a — rule_execution_logs table
-- Spec: docs/superpowers/specs/2026-05-18-canvas-rules-phase4a-spec.md §3
-- Created: 2026-05-18

CREATE TABLE rule_execution_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id UUID NOT NULL REFERENCES business_rules(id),
  factory_id VARCHAR(50) NOT NULL,
  trigger_event VARCHAR(100),
  input_json JSONB,
  result_json JSONB,
  executed_at TIMESTAMP DEFAULT NOW(),
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

CREATE INDEX idx_rule_logs_factory_rule
  ON rule_execution_logs (factory_id, rule_id, executed_at DESC)
  WHERE deleted_at IS NULL;

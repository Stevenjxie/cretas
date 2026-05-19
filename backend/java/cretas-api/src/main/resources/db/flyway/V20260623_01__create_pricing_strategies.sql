-- Canvas-Pricing Phase 4b — pricing_strategies
-- See docs/superpowers/specs/2026-05-18-canvas-pricing-phase4b-spec.md §2

CREATE TABLE pricing_strategies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  strategy_code VARCHAR(100) NOT NULL,
  strategy_name VARCHAR(255),
  strategy_type VARCHAR(20) NOT NULL,
  scope_filter_json JSONB DEFAULT '{}'::jsonb,
  rules_json JSONB DEFAULT '{}'::jsonb,
  priority INT NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  valid_from DATE,
  valid_to DATE,
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL,
  UNIQUE(factory_id, strategy_code)
);

CREATE INDEX idx_pricing_strategies_factory_active
  ON pricing_strategies (factory_id, enabled, valid_from, valid_to, priority)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE pricing_strategies IS
  'Canvas-Pricing Phase 4b — 5 strategy types: TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE';
COMMENT ON COLUMN pricing_strategies.priority IS
  'Lower number = higher priority. Default 100.';
COMMENT ON COLUMN pricing_strategies.rules_json IS
  'Type-specific JSON shape — see spec §1 for per-type schema.';

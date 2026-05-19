-- Canvas-Pricing Phase 4b — pricing_application_logs (audit trail)
-- See docs/superpowers/specs/2026-05-18-canvas-pricing-phase4b-spec.md §2

CREATE TABLE pricing_application_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  strategy_id UUID REFERENCES pricing_strategies(id),
  factory_id VARCHAR(50) NOT NULL,
  business_entity_type VARCHAR(50),
  business_entity_id VARCHAR(50),
  original_price NUMERIC(15,2),
  final_price NUMERIC(15,2),
  discount NUMERIC(15,2),
  applied_strategies JSONB DEFAULT '[]'::jsonb,
  applied_at TIMESTAMP DEFAULT NOW(),
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

CREATE INDEX idx_pricing_logs_factory_entity
  ON pricing_application_logs (factory_id, business_entity_id, applied_at DESC)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE pricing_application_logs IS
  'Canvas-Pricing Phase 4b — audit log per PricingEngine.calculate invocation. Finance team monthly review of cost-violation SOs uses this.';
COMMENT ON COLUMN pricing_application_logs.applied_strategies IS
  'JSONB array of {strategyId, strategyCode, type, discountApplied}.';

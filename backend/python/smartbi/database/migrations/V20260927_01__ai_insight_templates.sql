-- Chart Auto-Insight template library (U3)
-- Spec: docs/superpowers/specs/2026-06-10-chart-auto-insight-design.md §3
--
-- Table: ai_insight_templates
-- Purpose: stores LLM-generated structured insight templates for chart signatures.
--   - Tier2a cache: is_active=true entries are the fast path (0 LLM tokens)
--   - Distillation: proposal_count >= PROMOTE_THRESHOLD + guards → is_active=true
--
-- 🔒 RLS: tenant_isolation policy enforces factory_id = current_setting('app.factory_id')
--    so cross-tenant reads return zero rows even without application-level checks.
--
-- Versioning note: must be > V20260926_01 (current highest as of 2026-06-10)

CREATE TABLE IF NOT EXISTS ai_insight_templates (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(64) NOT NULL,
    signature_hash      VARCHAR(64) NOT NULL,
    chart_signature     JSONB,
    insight_template    JSONB NOT NULL,
    required_permission VARCHAR(64),
    source_type         VARCHAR(32) NOT NULL DEFAULT 'LLM_FALLBACK',
    confidence          NUMERIC(4, 3) DEFAULT 0.5,
    hit_count           INTEGER NOT NULL DEFAULT 0,
    proposal_count      INTEGER NOT NULL DEFAULT 0,
    is_active           BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ai_insight_sig_factory UNIQUE (signature_hash, factory_id)
);

-- Index for fast signature lookup (primary hot path: Tier2a lookup by sig + factory)
CREATE INDEX IF NOT EXISTS idx_ai_insight_sig_active
    ON ai_insight_templates (signature_hash, factory_id)
    WHERE is_active = TRUE;

-- Index for distillation admin queries (find promoted/unverified templates per factory)
CREATE INDEX IF NOT EXISTS idx_ai_insight_factory_active
    ON ai_insight_templates (factory_id, is_active, is_verified);

-- Trigger: auto-update updated_at on row modification
CREATE OR REPLACE FUNCTION _update_ai_insight_templates_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ai_insight_templates_updated_at ON ai_insight_templates;
CREATE TRIGGER trg_ai_insight_templates_updated_at
    BEFORE UPDATE ON ai_insight_templates
    FOR EACH ROW EXECUTE FUNCTION _update_ai_insight_templates_updated_at();

-- source_type constraint
ALTER TABLE ai_insight_templates
    ADD CONSTRAINT chk_ai_insight_source_type
        CHECK (source_type IN ('LLM_FALLBACK', 'FIXED_TEMPLATE'));

-- RLS: tenant isolation — each factory sees only its own templates
ALTER TABLE ai_insight_templates ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON ai_insight_templates;
CREATE POLICY tenant_isolation ON ai_insight_templates
    USING (factory_id = current_setting('app.factory_id', true));

-- GRANT DML to the smartbi application user
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_insight_templates TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE ai_insight_templates_id_seq TO smartbi_user;

COMMENT ON TABLE ai_insight_templates IS
    'Chart Auto-Insight template library. Tier2a active cache + distillation corpus. '
    'RLS enforces per-factory isolation. See spec §2.2.';

COMMENT ON COLUMN ai_insight_templates.signature_hash IS
    'SHA256(chartType|xDim|yMetric|aggregation|domain|dataPattern|permissionTier|factoryId)';
COMMENT ON COLUMN ai_insight_templates.insight_template IS
    'Structured JSON: {finding_tpl, implication_tpl?, suggestion_tpl?, slots[]}. '
    'All named values must use {placeholder} syntax — no literal store/product names.';
COMMENT ON COLUMN ai_insight_templates.required_permission IS
    'NULL = all roles; finance:read_write = only FINANCE_ROLES may receive this template.';
COMMENT ON COLUMN ai_insight_templates.source_type IS
    'LLM_FALLBACK: auto-generated via Tier2b. FIXED_TEMPLATE: manually curated.';
COMMENT ON COLUMN ai_insight_templates.is_active IS
    'True = Tier2a fast path. Set by distillation promote (proposal_count >= threshold + guards).';
COMMENT ON COLUMN ai_insight_templates.is_verified IS
    'True = manually reviewed. Required for suggestion-bearing templates before auto-promote.';
COMMENT ON COLUMN ai_insight_templates.proposal_count IS
    'Number of times this structural template was independently generated (distillation count).';

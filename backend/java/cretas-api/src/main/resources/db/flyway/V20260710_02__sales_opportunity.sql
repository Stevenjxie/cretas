-- Sprint 7 wave 2 Track T4 — 商机 8 阶段 CRM funnel (2026-05-20).
-- Plan: docs/superpowers/plans/2026-05-20-sprint-7-wave-2-tracks.md §T4
-- Context: HJ Round 13 §15 finding — 大客户 b2b 销售商机 8 阶段 funnel
--   (LEAD → QUALIFIED → DEMO → PROPOSAL → NEGOTIATE → VERBAL → CLOSED_WON/CLOSED_LOST)
-- Customer entity (PR #53 F merged) exists; this adds opportunity tracking on top.

-- ==================== Step 1: sales_opportunities 主表 ====================

CREATE TABLE IF NOT EXISTS sales_opportunities (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    customer_id VARCHAR(191) NOT NULL REFERENCES customers(id),
    title VARCHAR(200) NOT NULL,
    stage VARCHAR(20) NOT NULL,
    value_amount NUMERIC(14, 2),
    probability INTEGER NOT NULL DEFAULT 10,
    expected_close_date DATE,
    owner_id BIGINT NOT NULL REFERENCES users(id),
    closed_at TIMESTAMP NULL,
    description TEXT,
    created_by BIGINT NOT NULL REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 0,
    -- BaseEntity audit
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,

    -- Stage enum 校验
    CONSTRAINT chk_sales_opp_stage CHECK (
        stage IN ('LEAD', 'QUALIFIED', 'DEMO', 'PROPOSAL', 'NEGOTIATE',
                  'VERBAL', 'CLOSED_WON', 'CLOSED_LOST')
    ),
    -- Probability 0-100 校验
    CONSTRAINT chk_sales_opp_probability CHECK (
        probability >= 0 AND probability <= 100
    )
);

-- Indices on factory_id + customer_id + stage + owner_id
CREATE INDEX IF NOT EXISTS idx_sales_opp_factory
  ON sales_opportunities(factory_id);
CREATE INDEX IF NOT EXISTS idx_sales_opp_customer
  ON sales_opportunities(customer_id);
CREATE INDEX IF NOT EXISTS idx_sales_opp_stage
  ON sales_opportunities(stage);
CREATE INDEX IF NOT EXISTS idx_sales_opp_owner
  ON sales_opportunities(owner_id);
CREATE INDEX IF NOT EXISTS idx_sales_opp_expected_close
  ON sales_opportunities(expected_close_date);

-- BaseEntity updated_at trigger (function update_updated_at defined upstream).
DROP TRIGGER IF EXISTS trigger_sales_opp_updated_at ON sales_opportunities;
CREATE TRIGGER trigger_sales_opp_updated_at
BEFORE UPDATE ON sales_opportunities
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

COMMENT ON TABLE sales_opportunities IS
    'Sprint 7 wave 2 T4 - 销售商机 8 阶段 funnel. 大客户 b2b sales tracking.';

-- ==================== Step 2: opportunity_stage_history 审计表 ====================

CREATE TABLE IF NOT EXISTS opportunity_stage_history (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    opportunity_id VARCHAR(36) NOT NULL REFERENCES sales_opportunities(id),
    from_stage VARCHAR(20),
    to_stage VARCHAR(20) NOT NULL,
    reason TEXT,
    changed_by BIGINT NOT NULL REFERENCES users(id),
    changed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    -- BaseEntity audit
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,

    CONSTRAINT chk_osh_from_stage CHECK (
        from_stage IS NULL OR from_stage IN ('LEAD', 'QUALIFIED', 'DEMO', 'PROPOSAL',
            'NEGOTIATE', 'VERBAL', 'CLOSED_WON', 'CLOSED_LOST')
    ),
    CONSTRAINT chk_osh_to_stage CHECK (
        to_stage IN ('LEAD', 'QUALIFIED', 'DEMO', 'PROPOSAL', 'NEGOTIATE',
                     'VERBAL', 'CLOSED_WON', 'CLOSED_LOST')
    )
);

CREATE INDEX IF NOT EXISTS idx_osh_opp_changed
  ON opportunity_stage_history(opportunity_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_osh_factory
  ON opportunity_stage_history(factory_id);

DROP TRIGGER IF EXISTS trigger_osh_updated_at ON opportunity_stage_history;
CREATE TRIGGER trigger_osh_updated_at
BEFORE UPDATE ON opportunity_stage_history
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

COMMENT ON TABLE opportunity_stage_history IS
    'Sprint 7 wave 2 T4 - 商机阶段变更审计. 每次 transitionStage 写一条.';

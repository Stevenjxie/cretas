-- Sprint 7 wave 2 Track T5 — 业绩 6 项 sales target + commission + quota (2026-05-20).
-- Plan: docs/superpowers/plans/2026-05-20-sprint-7-wave-2-tracks.md §T5
-- Context: HJ Round 13 §15 业绩 6 项 (月/季/年 target / 实际 vs target gap /
--          销售排名 / 提成 commission / 团队 quota / 完成率). F006 销售必上.
-- Depends on: V20260710_02 (sales_opportunities, T4 ship).
-- Flyway version note: T3 parallel uses V20260720_01 (avoid collision).

-- ==================== Step 1: sales_targets 销售目标表 ====================

CREATE TABLE IF NOT EXISTS sales_targets (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES users(id),
    period VARCHAR(10) NOT NULL,
    year INTEGER NOT NULL,
    period_num INTEGER NOT NULL,
    target_amount NUMERIC(14, 2) NOT NULL,
    created_by BIGINT NOT NULL REFERENCES users(id),
    -- BaseEntity audit
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,

    -- Period enum 校验
    CONSTRAINT chk_sales_target_period CHECK (
        period IN ('MONTH', 'QUARTER', 'YEAR')
    ),
    -- period_num 上限校验 (MONTH 12 / QUARTER 4 / YEAR 1)
    CONSTRAINT chk_sales_target_period_num CHECK (
        (period = 'MONTH' AND period_num BETWEEN 1 AND 12)
        OR (period = 'QUARTER' AND period_num BETWEEN 1 AND 4)
        OR (period = 'YEAR' AND period_num = 1)
    ),
    -- 目标金额 > 0 且 <= 1000万 (R1 防呆)
    CONSTRAINT chk_sales_target_amount CHECK (
        target_amount > 0 AND target_amount <= 10000000
    ),

    -- 同一销售员同一周期同一年同一周期号 唯一
    CONSTRAINT uk_sales_target_unique UNIQUE (factory_id, owner_id, period, year, period_num)
);

CREATE INDEX IF NOT EXISTS idx_sales_target_factory ON sales_targets(factory_id);
CREATE INDEX IF NOT EXISTS idx_sales_target_owner ON sales_targets(owner_id);
CREATE INDEX IF NOT EXISTS idx_sales_target_period ON sales_targets(period, year, period_num);

DROP TRIGGER IF EXISTS trigger_sales_target_updated_at ON sales_targets;
CREATE TRIGGER trigger_sales_target_updated_at
BEFORE UPDATE ON sales_targets
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

COMMENT ON TABLE sales_targets IS
    'Sprint 7 wave 2 T5 - 销售业绩目标. 月度/季度/年度 target_amount.';

-- ==================== Step 2: commission_rules 提成规则表 ====================

CREATE TABLE IF NOT EXISTS commission_rules (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    sales_id BIGINT REFERENCES users(id),
    customer_type VARCHAR(50),
    percentage NUMERIC(5, 2) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL REFERENCES users(id),
    -- BaseEntity audit
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,

    -- 百分比 0-100 校验
    CONSTRAINT chk_comm_rule_percentage CHECK (
        percentage >= 0 AND percentage <= 100
    ),
    -- effective_to 必须 >= effective_from
    CONSTRAINT chk_comm_rule_effective CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    )
);

CREATE INDEX IF NOT EXISTS idx_comm_rule_factory ON commission_rules(factory_id);
CREATE INDEX IF NOT EXISTS idx_comm_rule_sales ON commission_rules(sales_id);
CREATE INDEX IF NOT EXISTS idx_comm_rule_customer_type ON commission_rules(customer_type);
CREATE INDEX IF NOT EXISTS idx_comm_rule_active ON commission_rules(active);

DROP TRIGGER IF EXISTS trigger_comm_rule_updated_at ON commission_rules;
CREATE TRIGGER trigger_comm_rule_updated_at
BEFORE UPDATE ON commission_rules
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

COMMENT ON TABLE commission_rules IS
    'Sprint 7 wave 2 T5 - 提成规则. sales_id NULL = 通用, customer_type NULL = 全客户类型.';

-- ==================== Step 3: commissions 提成记录表 ====================

CREATE TABLE IF NOT EXISTS commissions (
    id VARCHAR(36) PRIMARY KEY,
    factory_id VARCHAR(191) NOT NULL,
    sales_opportunity_id VARCHAR(36) NOT NULL REFERENCES sales_opportunities(id),
    rule_id VARCHAR(36) NOT NULL REFERENCES commission_rules(id),
    sales_id BIGINT NOT NULL REFERENCES users(id),
    percentage NUMERIC(5, 2) NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP NULL,
    -- BaseEntity audit
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,

    CONSTRAINT chk_comm_status CHECK (
        status IN ('PENDING', 'PAID', 'CANCELLED')
    ),
    CONSTRAINT chk_comm_percentage CHECK (
        percentage >= 0 AND percentage <= 100
    ),
    -- 每个商机仅产生一次提成 (除非软删除后重算)
    CONSTRAINT uk_comm_opportunity UNIQUE (sales_opportunity_id)
);

CREATE INDEX IF NOT EXISTS idx_comm_factory ON commissions(factory_id);
CREATE INDEX IF NOT EXISTS idx_comm_opportunity ON commissions(sales_opportunity_id);
CREATE INDEX IF NOT EXISTS idx_comm_sales ON commissions(sales_id);
CREATE INDEX IF NOT EXISTS idx_comm_status ON commissions(status);

DROP TRIGGER IF EXISTS trigger_comm_updated_at ON commissions;
CREATE TRIGGER trigger_comm_updated_at
BEFORE UPDATE ON commissions
FOR EACH ROW EXECUTE FUNCTION update_updated_at();

COMMENT ON TABLE commissions IS
    'Sprint 7 wave 2 T5 - 提成记录. 由 SalesOpportunity CLOSED_WON event 触发自动计算.';

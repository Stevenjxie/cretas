-- Sprint 6 Track W4-B: WagePolicy 按时/混合 mode + 月底自动 trigger 工资凭证
--
-- 背景: Sprint 5 PR #57 E ship Wage trigger (ProcessingService 钩 + PieceRateRule).
-- 只 ship 按件 (PIECE_RATE) mode. F006 卤制品客户: 部分员工按时 (HOURLY) /
-- 部分员工混合 (MIXED: 基础工时 + 计件提成).
--
-- 本次新增 3 表:
--   1. wage_policy           — 员工/工厂级 wage mode 配置 (PIECE_RATE/HOURLY/MIXED)
--   2. hourly_rate_rule      — HOURLY mode 时薪规则 (effective period)
--   3. wage_calculation      — 月度计算结果明细 (audit + idempotent upsert)
--
-- 兼容性: 不修改 payroll_records / piece_rate_rule / wage_record_trigger 等 PR #57 E 表.
-- mode 默认 PIECE_RATE 保证 PR #57 E flow 向后兼容.

-- ==================== 1. wage_policy ====================
CREATE TABLE IF NOT EXISTS wage_policy (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    employee_id         BIGINT NULL,
    mode                VARCHAR(20) NOT NULL DEFAULT 'PIECE_RATE',
    mixed_formula_hint  VARCHAR(200),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP NULL,
    CONSTRAINT chk_wage_policy_mode CHECK (mode IN ('PIECE_RATE', 'HOURLY', 'MIXED'))
);

CREATE INDEX IF NOT EXISTS idx_wage_policy_factory
    ON wage_policy(factory_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_wage_policy_employee
    ON wage_policy(factory_id, employee_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_wage_policy_active
    ON wage_policy(factory_id, is_active) WHERE deleted_at IS NULL;

COMMENT ON TABLE wage_policy IS
    'Sprint 6 Track W4-B: 工资策略 (员工 OR 工厂默认). employeeId=NULL 为 factory-level default. PR #57 E mode 默认 PIECE_RATE.';
COMMENT ON COLUMN wage_policy.mode IS
    'PIECE_RATE/HOURLY/MIXED. 默认 PIECE_RATE 兼容 PR #57 E.';
COMMENT ON COLUMN wage_policy.mixed_formula_hint IS
    'MIXED mode UI hint, 不参与 calc. 当前 calc 简化为 HOURLY 全额 + PIECE_RATE 全额.';

-- ==================== 2. hourly_rate_rule ====================
CREATE TABLE IF NOT EXISTS hourly_rate_rule (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    employee_id         BIGINT NOT NULL,
    base_hourly_rate    NUMERIC(10, 2) NOT NULL,
    overtime_multiplier NUMERIC(4, 2) NOT NULL DEFAULT 1.50,
    effective_from      DATE NOT NULL,
    effective_to        DATE NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP NULL,
    CONSTRAINT chk_hourly_rate_positive CHECK (base_hourly_rate > 0),
    CONSTRAINT chk_overtime_mult_positive CHECK (overtime_multiplier > 0),
    CONSTRAINT chk_effective_period_valid
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX IF NOT EXISTS idx_hourly_rate_factory
    ON hourly_rate_rule(factory_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_hourly_rate_employee
    ON hourly_rate_rule(factory_id, employee_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_hourly_rate_effective
    ON hourly_rate_rule(factory_id, employee_id, effective_from, effective_to)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE hourly_rate_rule IS
    'Sprint 6 Track W4-B: 时薪规则 (HOURLY/MIXED mode 用). 一员工可多条历史规则, 通过 effective 期间区分.';
COMMENT ON COLUMN hourly_rate_rule.base_hourly_rate IS
    '基础时薪 (元/小时). UI 端 :max="500" 防呆 per fool-proof-design.md Rule 1.';
COMMENT ON COLUMN hourly_rate_rule.overtime_multiplier IS
    '加班倍率 (默认 1.5). 加班金额 = overtime_hours × base_hourly_rate × overtime_multiplier.';

-- ==================== 3. wage_calculation ====================
CREATE TABLE IF NOT EXISTS wage_calculation (
    id                  BIGSERIAL PRIMARY KEY,
    factory_id          VARCHAR(50) NOT NULL,
    employee_id         BIGINT NOT NULL,
    employee_name       VARCHAR(100),
    period_month        DATE NOT NULL,
    mode                VARCHAR(20) NOT NULL,
    hourly_amount       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    overtime_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    piece_rate_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_hours         NUMERIC(8, 2) NOT NULL DEFAULT 0,
    overtime_hours      NUMERIC(8, 2) NOT NULL DEFAULT 0,
    total_piece_count   INTEGER NOT NULL DEFAULT 0,
    hourly_rule_id      BIGINT NULL,
    piece_rule_id       BIGINT NULL,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP NULL,
    CONSTRAINT chk_wage_calc_mode CHECK (mode IN ('PIECE_RATE', 'HOURLY', 'MIXED'))
);

CREATE INDEX IF NOT EXISTS idx_wage_calc_factory
    ON wage_calculation(factory_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_wage_calc_employee
    ON wage_calculation(factory_id, employee_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_wage_calc_period
    ON wage_calculation(factory_id, period_month) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_wage_calc_employee_period
    ON wage_calculation(factory_id, employee_id, period_month)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE wage_calculation IS
    'Sprint 6 Track W4-B: 月度工资计算明细 (audit). idempotent upsert on (factoryId, employeeId, periodMonth).';
COMMENT ON COLUMN wage_calculation.period_month IS
    '计算周期月份 (该月 1 号). 例 2026-05-01 = 2026 年 5 月.';
COMMENT ON COLUMN wage_calculation.mode IS
    'PIECE_RATE/HOURLY/MIXED. derived total = (mode==PIECE_RATE ? piece_rate_amount : hourly_amount + overtime_amount + (mode==MIXED ? piece_rate_amount : 0)).';

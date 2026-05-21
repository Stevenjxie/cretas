-- Sprint 9 P2.E — SSOP 清洁消毒记录 (Sanitation Standard Operating Procedures)
-- 法定: GB 14881-2013 食品生产通用卫生规范
-- 3 表: ssop_procedures (标准模板) + ssop_execution_records (每日执行) + ssop_blocking_gates (阻产 audit)

-- ============================================================
-- Table 1: ssop_procedures — 清洁标准模板
-- ============================================================
CREATE TABLE IF NOT EXISTS ssop_procedures (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    procedure_code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    target VARCHAR(30) NOT NULL,
    frequency VARCHAR(20) NOT NULL,
    steps TEXT,
    chemicals_used TEXT,
    inspector_role VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_ssop_procedures_factory_code
        UNIQUE (factory_id, procedure_code),
    CONSTRAINT chk_ssop_procedures_target
        CHECK (target IN ('EQUIPMENT', 'TOOLS', 'ENVIRONMENT', 'EMPLOYEE', 'PEST_CONTROL')),
    CONSTRAINT chk_ssop_procedures_frequency
        CHECK (frequency IN ('DAILY', 'SHIFT', 'WEEKLY', 'MONTHLY'))
);

CREATE INDEX IF NOT EXISTS idx_ssop_procedures_factory_active
    ON ssop_procedures (factory_id, active);
CREATE INDEX IF NOT EXISTS idx_ssop_procedures_target_frequency
    ON ssop_procedures (factory_id, target, frequency, active);

COMMENT ON TABLE ssop_procedures IS 'SSOP 清洁标准模板 (Sprint 9 P2.E, GB 14881-2013)';
COMMENT ON COLUMN ssop_procedures.target IS '清洁对象: EQUIPMENT/TOOLS/ENVIRONMENT/EMPLOYEE/PEST_CONTROL';
COMMENT ON COLUMN ssop_procedures.frequency IS '执行频率: DAILY/SHIFT/WEEKLY/MONTHLY';
COMMENT ON COLUMN ssop_procedures.inspector_role IS '检验员角色 e.g. quality_mgr';

-- ============================================================
-- Table 2: ssop_execution_records — 每日清洁执行记录
-- ============================================================
CREATE TABLE IF NOT EXISTS ssop_execution_records (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    procedure_id BIGINT NOT NULL,
    execution_date DATE NOT NULL,
    execution_time TIME,
    executor_user_id BIGINT,
    shift VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SCHEDULED',
    completed_at TIMESTAMP,
    inspector_user_id BIGINT,
    inspector_signed_at TIMESTAMP,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL,
    CONSTRAINT chk_ssop_records_shift
        CHECK (shift IN ('MORNING', 'AFTERNOON', 'NIGHT')),
    CONSTRAINT chk_ssop_records_status
        CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX IF NOT EXISTS idx_ssop_records_factory_date_status
    ON ssop_execution_records (factory_id, execution_date, status);
CREATE INDEX IF NOT EXISTS idx_ssop_records_procedure_date
    ON ssop_execution_records (procedure_id, execution_date);
CREATE INDEX IF NOT EXISTS idx_ssop_records_executor
    ON ssop_execution_records (factory_id, executor_user_id, execution_date);

COMMENT ON TABLE ssop_execution_records IS 'SSOP 清洁执行记录 (每日 scheduler 自动创 + 人工 sign-off)';
COMMENT ON COLUMN ssop_execution_records.shift IS '班次: MORNING/AFTERNOON/NIGHT';
COMMENT ON COLUMN ssop_execution_records.status IS '状态机: SCHEDULED -> IN_PROGRESS -> COMPLETED/FAILED, 或 SCHEDULED -> SKIPPED';

-- ============================================================
-- Table 3: ssop_blocking_gates — 生产阻产 audit
-- ============================================================
CREATE TABLE IF NOT EXISTS ssop_blocking_gates (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    production_plan_id VARCHAR(191) NOT NULL,
    blocked_at TIMESTAMP NOT NULL,
    block_reason TEXT NOT NULL,
    failed_procedure_ids JSONB NOT NULL,
    resolved_at TIMESTAMP,
    resolved_by BIGINT,
    resolved_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL
);

CREATE INDEX IF NOT EXISTS idx_ssop_gates_factory_blocked
    ON ssop_blocking_gates (factory_id, blocked_at);
CREATE INDEX IF NOT EXISTS idx_ssop_gates_plan
    ON ssop_blocking_gates (production_plan_id);
CREATE INDEX IF NOT EXISTS idx_ssop_gates_unresolved
    ON ssop_blocking_gates (factory_id, resolved_at);

COMMENT ON TABLE ssop_blocking_gates IS 'SSOP 生产阻产 audit (production gate fail trail, 第三方 audit 必查)';
COMMENT ON COLUMN ssop_blocking_gates.failed_procedure_ids IS 'JSON List<Long> 未完成的 ssop_execution_records.id';
COMMENT ON COLUMN ssop_blocking_gates.resolved_at IS 'NULL = 未解决; 非 NULL = 操作员补做 + sign-off 后解决';

-- V20260820_03__haccp_checkpoints.sql
--
-- Sprint 8 P3 食品安全召回 — HACCP CCP 配置 + 监控记录 (2026-05-20)
-- 法定: 食品安全法 + GB/T 27341 HACCP 实施指南
--
-- Tables:
--   haccp_checkpoints           — CCP 配置 (CCP-01 / CCP-02 ...)
--   haccp_monitoring_records    — 每次实际监控记录 (跟 batch 关联)
--
-- Pattern mirrors V20260820_02 (PG standard). 所有 BaseEntity audit fields
-- (created_at / updated_at / deleted_at) 含在每个表 — per
-- .claude/rules/database-entity-sync.md HARD rule.

-- ===== 1. haccp_checkpoints (CCP 配置表) =====

CREATE TABLE IF NOT EXISTS haccp_checkpoints (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    checkpoint_code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    hazard_type VARCHAR(20) NOT NULL CHECK (hazard_type IN ('BIOLOGICAL','CHEMICAL','PHYSICAL')),
    description TEXT,
    critical_limit_min DECIMAL(19,4) NOT NULL,
    critical_limit_max DECIMAL(19,4) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    monitoring_procedure TEXT,
    corrective_action TEXT,
    verification_procedure TEXT,
    record_keeping TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uk_haccp_checkpoint_factory_code UNIQUE (factory_id, checkpoint_code)
);

CREATE INDEX IF NOT EXISTS idx_haccp_checkpoints_factory
    ON haccp_checkpoints(factory_id);

CREATE INDEX IF NOT EXISTS idx_haccp_checkpoints_active
    ON haccp_checkpoints(factory_id, active)
    WHERE deleted_at IS NULL;

-- ===== 2. haccp_monitoring_records (监控记录表) =====

CREATE TABLE IF NOT EXISTS haccp_monitoring_records (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    checkpoint_id BIGINT NOT NULL,
    batch_number VARCHAR(100) NOT NULL,
    monitoring_time TIMESTAMP NOT NULL,
    measured_value DECIMAL(19,4) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    is_deviation BOOLEAN NOT NULL DEFAULT FALSE,
    deviation_action TEXT,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_haccp_monitoring_factory_batch
    ON haccp_monitoring_records(factory_id, batch_number);

CREATE INDEX IF NOT EXISTS idx_haccp_monitoring_deviation
    ON haccp_monitoring_records(factory_id, is_deviation)
    WHERE is_deviation = TRUE AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_haccp_monitoring_checkpoint
    ON haccp_monitoring_records(checkpoint_id);

COMMENT ON TABLE haccp_checkpoints IS 'HACCP 关键控制点配置 — Sprint 8 P3 / GB/T 27341';
COMMENT ON TABLE haccp_monitoring_records IS 'HACCP 监控记录 — Sprint 8 P3 / 召回追溯主表';

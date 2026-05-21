-- V20260820_05__recall_events.sql
--
-- Sprint 8 P3 食品安全 — 召回事件 + 关联行动 (2026-05-20)
-- 法定: 食品安全法第 63 条 (强制召回 + 通知 + 记录)
--
-- Tables:
--   recall_events    — 召回事件主表 (status 流转: INVESTIGATING → NOTIFYING → FROZEN → REPORTED → COMPLETED)
--   recall_actions   — 召回事件关联行动 (一对多, action_type 涵盖 FREEZE_INVENTORY / NOTIFY_CUSTOMER / etc)

-- ===== 1. recall_events (召回事件主表) =====

CREATE TABLE IF NOT EXISTS recall_events (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    event_code VARCHAR(100) NOT NULL,
    trigger_reason TEXT NOT NULL,
    affected_product_category VARCHAR(100) NOT NULL,
    trigger_time TIMESTAMP NOT NULL DEFAULT NOW(),
    triggered_by_user_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'INVESTIGATING'
        CHECK (status IN ('INVESTIGATING','NOTIFYING','FROZEN','REPORTED','COMPLETED')),
    completed_at TIMESTAMP,
    estimated_loss DECIMAL(19,2),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uk_recall_event_code UNIQUE (event_code)
);

CREATE INDEX IF NOT EXISTS idx_recall_events_factory
    ON recall_events(factory_id, trigger_time DESC);

CREATE INDEX IF NOT EXISTS idx_recall_events_status
    ON recall_events(factory_id, status)
    WHERE deleted_at IS NULL;

-- ===== 2. recall_actions (召回行动表) =====

CREATE TABLE IF NOT EXISTS recall_actions (
    id BIGSERIAL PRIMARY KEY,
    recall_event_id BIGINT NOT NULL,
    action_type VARCHAR(50) NOT NULL
        CHECK (action_type IN ('FREEZE_INVENTORY','NOTIFY_CUSTOMER','REGULATORY_REPORT','CUSTOMER_REPLY','COMPLETION_AUDIT')),
    target_entity_type VARCHAR(30) NOT NULL,
    target_entity_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    executed_at TIMESTAMP,
    execution_notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_recall_actions_event
    ON recall_actions(recall_event_id, created_at);

CREATE INDEX IF NOT EXISTS idx_recall_actions_status
    ON recall_actions(recall_event_id, status);

COMMENT ON TABLE recall_events IS '食品召回事件主表 — Sprint 8 P3 / 食品安全法第 63 条';
COMMENT ON TABLE recall_actions IS '召回行动记录 — Sprint 8 P3 / 闭环追踪';

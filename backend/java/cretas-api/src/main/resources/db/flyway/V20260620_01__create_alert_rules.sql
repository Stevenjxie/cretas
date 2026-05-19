-- Phase 2 Canvas-Alerts skeleton: AlertRule 配置表.
-- 由 entity com.cretas.aims.entity.alerts.AlertRule 映射.
-- Spec: docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md §3

CREATE TABLE IF NOT EXISTS alert_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  factory_id VARCHAR(50) NOT NULL,
  alert_type VARCHAR(50) NOT NULL,
  rule_name VARCHAR(255) NOT NULL,
  trigger_condition_spel TEXT,
  severity VARCHAR(10) NOT NULL DEFAULT 'MID',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  notify_channels JSONB DEFAULT '[]'::jsonb,
  notify_roles JSONB DEFAULT '[]'::jsonb,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

-- 唯一约束 — 工厂内 ruleName 不可重复 (软删除后允许同名重建).
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_rules_factory_name
  ON alert_rules (factory_id, rule_name)
  WHERE deleted_at IS NULL;

-- 索引 — scheduler / listener 主查询路径.
CREATE INDEX IF NOT EXISTS idx_alert_rules_factory_enabled
  ON alert_rules (factory_id, enabled)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE alert_rules IS 'Phase 2 Canvas-Alerts 告警规则配置 — per-factory 可配, 支持 8 alert types';
COMMENT ON COLUMN alert_rules.alert_type IS '8 选 1: INVENTORY_LOW / INVENTORY_EXPIRING / QUALITY_ANOMALY / PO_AMOUNT_THRESHOLD / SO_AMOUNT_THRESHOLD / SALES_DECLINE / CUSTOMER_PAYMENT_OVERDUE / SUPPLIER_PAYABLE_DUE';
COMMENT ON COLUMN alert_rules.trigger_condition_spel IS 'SpEL 表达式, 评估时绑定 #context.* 业务上下文. NULL 表示用 type 内置默认逻辑';
COMMENT ON COLUMN alert_rules.severity IS 'LOW / MID / HIGH — 决定通知优先级';
COMMENT ON COLUMN alert_rules.notify_channels IS 'JSONB list: [WECHAT, DINGTALK, EMAIL, IN_APP]';
COMMENT ON COLUMN alert_rules.notify_roles IS 'JSONB list: 接收角色 code (e.g. procurement_manager)';

-- Phase 2 Canvas-Alerts skeleton: AlertEvent 触发事件表.
-- 由 entity com.cretas.aims.entity.alerts.AlertEvent 映射.
-- Spec: docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md §3

CREATE TABLE IF NOT EXISTS alert_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_id UUID NOT NULL REFERENCES alert_rules(id),
  factory_id VARCHAR(50) NOT NULL,
  business_entity_type VARCHAR(50),
  business_entity_id VARCHAR(191),
  severity VARCHAR(10) NOT NULL DEFAULT 'MID',
  message TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  acked_by_user_id BIGINT,
  acked_at TIMESTAMP,
  resolved_by_user_id BIGINT,
  resolved_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

-- 索引 — dashboard / event-history 主查询.
CREATE INDEX IF NOT EXISTS idx_alert_events_factory_status_created
  ON alert_events (factory_id, status, created_at DESC)
  WHERE deleted_at IS NULL;

-- 索引 — rule_id 反查 (单规则 KPI / dedup window).
CREATE INDEX IF NOT EXISTS idx_alert_events_rule
  ON alert_events (rule_id)
  WHERE deleted_at IS NULL;

-- 索引 — 业务实体反查 (e.g. PO 详情页显示相关告警).
CREATE INDEX IF NOT EXISTS idx_alert_events_business_entity
  ON alert_events (factory_id, business_entity_type, business_entity_id)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE alert_events IS 'Phase 2 Canvas-Alerts 告警触发事件记录, 状态机 OPEN→ACKNOWLEDGED→RESOLVED';
COMMENT ON COLUMN alert_events.severity IS 'snapshot from alert_rules.severity at creation time (规则后续变化不追溯)';
COMMENT ON COLUMN alert_events.message IS '人读告警消息, 含具体数字 (e.g. "库存 25kg 低于阈值 30kg")';
COMMENT ON COLUMN alert_events.status IS 'OPEN / ACKNOWLEDGED / RESOLVED';
COMMENT ON COLUMN alert_events.business_entity_type IS 'e.g. MATERIAL_BATCH / PURCHASE_ORDER / SALES_ORDER';

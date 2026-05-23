-- V20260823_06__d5_alert_intent.sql
--
-- Sprint 11 D5 — 注册 1 个 Tool-level intent:
--   INDICATOR_ALERT  → indicator_alert (READ, LOW)
--
-- 由 ai/tool/impl/indicator/IndicatorAlertTool.java 实现.
-- 扫描工厂全部启用指标的最新快照, 评估 threshold 命中, 返回告警列表.
--
-- Pattern mirrors V20260823_05 D4 INDICATOR_COMPARISON. 3-strike preflight:
--   - NO `||` 在 string literal
--   - JSONB literal keywords array
--   - tool_name 非 NULL (Tool 直接执行)
--   - intent_category=QUERY

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'INDICATOR_ALERT', '指标告警扫描', 'QUERY',
     'indicator_alert', 'LOW',
     '["告警","报警","红灯","黄灯","什么需要关注","有问题吗","现在状态如何","几个红灯","几个黄灯","哪个指标在报警","告警列表","出问题了吗","看看今天","当前告警","厨房有问题吗","车间有问题吗"]',
     'Sprint 11 D5 — 指标告警 Tool. 扫描工厂全部启用指标当前 breach 状态, 返回 RED/YELLOW 列表 (按 severity 倒序). 可选 min_severity (ALL/WARNING/ALERT) + category 过滤. 触发: 现在有几个红灯 / 什么需要关注 / 告警列表 / 哪个有问题. Sprint 12 Day 9 RecomputeScheduler merge 后接 schedule hook 触发实时 alert.',
     85, true, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

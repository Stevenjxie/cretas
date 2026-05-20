-- V20260820_01__sprint8_p1_workdesk_intents.sql
--
-- Sprint 8 P1 — 卤味老板 Workdesk V1 (2026-05-20)
-- 注册 1 Skill 顶层 intent + 8 Tool-level intents.
--
-- Skill 触发 (Workdesk auto-trigger entry point):
--   DAILY_CUSTOMER_FOLLOWUP → daily-customer-followup Skill
--
-- 8 Tool intents (LLM 直接绑 tool_name 走 Tool 直接执行分支):
--   CUSTOMER_PRIORITY_QUERY      → customer_priority_query
--   WECHAT_RECENT_QUERY          → wechat_record_recent_query
--   WECHAT_CREATE                → wechat_record_create (WRITE + Preview)
--   CALL_FOLLOWUP_PENDING        → call_record_followup_pending
--   OPPORTUNITY_STAGE_ALERT      → opportunity_stage_alert
--   OPPORTUNITY_TRANSITION       → opportunity_transition_stage (WRITE + Preview)
--   CUSTOMER_REVENUE_TREND       → customer_revenue_trend
--   PROCESSING_CAPACITY_TODAY    → processing_capacity_today
--
-- Pattern mirrors V20260516_05__print_document_intent.sql (proven).
-- ON CONFLICT (intent_code) DO UPDATE — idempotent re-run safe.

-- ===== 1. Workdesk-level Skill intent (highest priority for auto-trigger) =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'DAILY_CUSTOMER_FOLLOWUP',
    '今日客户跟进',
    'WORKDESK',
    NULL,  -- skill 模式, tool_name 留空, 走 Skill router
    'LOW',
    '["今天跟谁","今日跟进","我的客户跟进","今天该跟谁","跟进列表","客户跟进清单","今天打谁电话","今日要跟进哪些客户","我的销售工作台"]',
    'Sprint 8 P1 卤味老板 Workdesk V1 入口 — 聚合 5 Tool 输出今日 🔴🟡🟢 客户跟进清单. ' ||
    '触发 daily-customer-followup Skill (定义于 SkillRegistryImpl.initializeDefaultSkills).',
    50,
    true,
    NOW(),
    NOW()
)
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

-- ===== 2. 8 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'CUSTOMER_PRIORITY_QUERY', '客户优先级排序', 'QUERY',
     'customer_priority_query', 'LOW',
     '["客户优先级","重点客户","客户排序","按重要性排","VIP客户","重点客户清单"]',
     '按客户重要性 + 商机阶段聚合排序输出客户清单. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'WECHAT_RECENT_QUERY', '查询近期微信', 'QUERY',
     'wechat_record_recent_query', 'LOW',
     '["微信记录","最近微信","客户微信","微信跟进","未回微信","查微信"]',
     '查近 N 天微信记录, 默认 7 天. 可选客户过滤. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'WECHAT_CREATE', '补录微信记录', 'DATA_OP',
     'wechat_record_create', 'MEDIUM',
     '["补录微信","添加微信记录","记一下微信","新增微信","加微信记录"]',
     '补录客户微信对话. WRITE + Preview. 防呆 R4 5min dedup window.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'CALL_FOLLOWUP_PENDING', 'missed call 跟进', 'QUERY',
     'call_record_followup_pending', 'LOW',
     '["未接电话","missed call","电话跟进","回电列表","电话待办","回访电话"]',
     '查近 N 天 MISSED 类型通话, 默认 7 天. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'OPPORTUNITY_STAGE_ALERT', '商机超 SLA 警告', 'QUERY',
     'opportunity_stage_alert', 'LOW',
     '["商机预警","商机超时","stale opportunity","商机停滞","商机超过","需要催的商机"]',
     '查超 SLA 天数 (默认 21d) 未推进的活跃商机. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'OPPORTUNITY_TRANSITION', '推进商机阶段', 'DATA_OP',
     'opportunity_transition_stage', 'MEDIUM',
     '["推进商机","商机阶段","商机进度","成单","签了","商机黄了","商机赢单","商机丢单"]',
     '推进商机到下一阶段 (state machine 含跳级 / 回退 / 终态重激活校验). WRITE + Preview.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'CUSTOMER_REVENUE_TREND', '客户收入趋势', 'ANALYSIS',
     'customer_revenue_trend', 'LOW',
     '["客户收入","收入趋势","客户订单趋势","本月 vs 上月","客户购买金额","收入变化"]',
     '聚合销售订单按月输出收入趋势. 可选单客户. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'PROCESSING_CAPACITY_TODAY', '今日产能', 'QUERY',
     'processing_capacity_today', 'LOW',
     '["今日产能","今天能做多少","产能查询","今日产线","今天能完成多少订单","今日生产"]',
     '聚合今天的 ProductionBatch 输出当日产能汇总. read-only.',
     80, true, NOW(), NOW())

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

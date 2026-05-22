-- V20260822_08__sprint10_loop4_approval_intents_recover.sql
--
-- Recovery migration: V_22_05 slot was taken by Loop 5's earlier filename
-- (production_loop_intents.sql) before rename to V_22_07. Loop 4 approval
-- intents in V_22_05__approval_loop_intents.sql NEVER applied (Flyway saw
-- V_22_05 already in history → skipped re-apply).
--
-- Per feedback_3_strike_comprehensive_audit_over_reactive_patching HARD —
-- N+1 strike of Flyway version collision marathon. Comprehensive fix tracked
-- separately; this migration recovers the 3 missing approval intents.
--
-- Original spec: V_22_05__approval_loop_intents.sql (Loop 4)
-- Same INSERT bodies, idempotent (ON CONFLICT DO UPDATE).

-- ===== 1. WORKDESK-level intent =====
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'MY_APPROVAL_WORKDESK',
    '我的审批工作台',
    'WORKDESK',
    'approval_pending_query',
    'LOW',
    '["我该批什么","等我审批的有哪些","我的审批","审批工作台","待我审批","今日审批清单","当前节点待批","我有多少要审","等我批的"]'::jsonb,
    'Sprint 10 Loop 4 审批工作台入口 — recovery via V_22_08 after V_22_05 slot collision',
    50, true, NOW(), NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name, intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category, sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords, description = EXCLUDED.description,
    priority = EXCLUDED.priority, is_active = EXCLUDED.is_active, updated_at = NOW();

-- ===== 2. APPROVAL_PENDING_QUERY =====
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'APPROVAL_PENDING_QUERY',
    '待审批列表查询',
    'QUERY',
    'approval_pending_query',
    'LOW',
    '["待审批","今日待审","当前节点待批","待审清单","等我审批","审批列表","我要审什么","审批待办","待办审批","我的待办"]'::jsonb,
    'Sprint 10 Loop 4 — 查当前用户 role 待审批 workflow 实例列表',
    80, true, NOW(), NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name, intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category, sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords, description = EXCLUDED.description,
    priority = EXCLUDED.priority, is_active = EXCLUDED.is_active, updated_at = NOW();

-- ===== 3. APPROVAL_ACTION_EXECUTE =====
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'APPROVAL_ACTION_EXECUTE',
    '执行审批操作',
    'DATA_OP',
    'approval_action_execute',
    'MEDIUM',
    '["批准","审批通过","同意审批","通过","拒绝","驳回","审批拒绝","不同意","审批","批了","驳了","同意","通过审批","批准这个","拒绝这个"]'::jsonb,
    'Sprint 10 Loop 4 — 执行 APPROVE / REJECT 审批转换',
    70, true, NOW(), NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name, intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category, sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords, description = EXCLUDED.description,
    priority = EXCLUDED.priority, is_active = EXCLUDED.is_active, updated_at = NOW();

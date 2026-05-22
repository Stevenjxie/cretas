-- V20260822_04__approval_loop_intents.sql
--
-- Sprint 10 Loop 4 — 审批闭环 AI intents (付款 / 开票 / 调价 / 采购 / 销售订单 通用).
--
-- 3 intents 注册:
--   1. APPROVAL_PENDING_QUERY  (Tool, QUERY)    — "我该批什么" / "今日待审" / "待审批"
--   2. APPROVAL_ACTION_EXECUTE (Tool, DATA_OP)  — 批准 / 拒绝 / 通过 / 驳回
--   3. MY_APPROVAL_WORKDESK    (WORKDESK, tool_name=approval_pending_query) — Workdesk 入口
--      Mirror Sprint 10 Loop 1 V20260822_03 pattern: WORKDESK intent 直绑 Tool 避免 Skill registry miss.
--
-- Pattern mirrors V20260822_03 (Sprint 10 Loop 1 shipment, proven).
-- ON CONFLICT (intent_code) DO UPDATE — idempotent re-run safe.
--
-- 3-strike preflight compliance:
--   - NO `||` in COMMENT (single string literal only — per PR #17/#18 PG syntax fixes)
--   - `::jsonb` explicit cast on keywords (per V20260821_36 hotfix convention)
--   - WORKDESK intent 直绑 Tool 而非 Skill (Loop 1 proven pattern)

-- ===== 1. WORKDESK-level intent (path A 入口, auto-trigger on Workdesk mount) =====

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
    'approval_pending_query',  -- 直绑 Tool (Loop 1 V20260822_03 proven pattern; 无 Skill registry miss)
    'LOW',
    '["我该批什么","等我审批的有哪些","我的审批","审批工作台","待我审批","今日审批清单","当前节点待批","我有多少要审","等我批的"]'::jsonb,
    'Sprint 10 Loop 4 审批工作台入口 — Workdesk auto-trigger 或用户主动问 "我该批什么". 路由到 approval_pending_query Tool 列出当前 role 待审 RUNNING workflow 实例.',
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

-- ===== 2. APPROVAL_PENDING_QUERY (Tool QUERY) — 列出当前 role 待审 =====

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
    'Sprint 10 Loop 4 — 查当前用户 role 待审批 workflow 实例列表 (跨 module 或 filter). 返 businessSummary + currentNodeLabel + 发起人. read-only.',
    80,
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

-- ===== 3. APPROVAL_ACTION_EXECUTE (Tool DATA_OP) — 批准 / 拒绝 =====

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
    'Sprint 10 Loop 4 — 执行 APPROVE / REJECT 审批转换. 包装 transitionNode. @Version 乐观锁防并发, 已结束实例返 409. context_json 加 testRun + source 标记.',
    70,
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

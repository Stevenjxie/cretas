-- V20260822_02__receive_loop_intents.sql
--
-- Sprint 10 Loop 2 — 入库/收货 AI 闭环 intents.
--
-- 注册 2 intents:
--   1. WAREHOUSE_TODAY_RECEIVING_PENDING (WORKDESK) — Path A/B 触发 → list 今日 PO 待收
--      映射到现有 Tool: material_today_receiving_query (Sprint 8 P4a already registered V20260820_08)
--      为什么再加一个 intent: Sprint 8 P4a 已注册 MATERIAL_TODAY_RECEIVING_QUERY 覆盖
--      "今日待收/今日到货" 等关键词. Sprint 10 brief 要求新增"今日 PO 待收","什么 PO
--      该入库了"等更口语化的 Path A/B 短语, 作为 WORKDESK category, tool_name=NULL 让
--      orchestrator 走默认路由.
--
--   2. RECEIVE_CONFIRM_CREATE (DATA_OP) — 一气呵成 创建+confirm Tool
--      映射到新 Tool: receive_confirm_create (本 PR 落地)
--
-- Pattern mirrors V20260820_08 (P4a proven). ON CONFLICT DO UPDATE for idempotent re-run.

-- ===== 1. Workdesk Path A/B trigger intent =====
-- 注: tool_name=NULL 因为 WORKDESK category 走 orchestrator 默认编排 (per Sprint 10 brief).
--    实际触发时 orchestrator 会 fall back 到 keyword/semantic match 走 MATERIAL_TODAY_RECEIVING_QUERY
--    或 RECEIVE_CONFIRM_CREATE. Sprint 11+ 可改用 Skill 编排聚合 today_receiving + disposal + qc.

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'WAREHOUSE_TODAY_RECEIVING_PENDING',
    '今日 PO 待收清单',
    'WORKDESK',
    NULL,
    'LOW',
    '["今日 PO 待收","今天要收什么货","今天到货什么","什么 PO 该入库了","今日待收 PO","哪些 PO 今天到","今日 PO 入库清单","今日待入库","今日 PO 收货","查看今日 PO 待收"]'::jsonb,
    'Sprint 10 Loop 2 Path A/B 触发 — 列出今日 PO 待收 (status=CONFIRMED + expectedArrivalDate<=today + 未完全入库). ' ||
    '复用 Sprint 8 P4a material_today_receiving_query Tool, 但 keywords 偏 Loop 2 文案. ' ||
    '客户原话 (F006 张权): 仓管员要"零认知负荷收货".',
    46,
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

-- ===== 2. Tool-level intent for 收货确认 ====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'RECEIVE_CONFIRM_CREATE',
    '一键收货确认 (Sprint 10 Loop 2)',
    'DATA_OP',
    'receive_confirm_create',
    'MEDIUM',
    '["确认收货","签收入库","一键收货","确认到货","签收 PO","收货确认","入库并确认","直接入库","收完确认","签收并入库"]'::jsonb,
    'Sprint 10 Loop 2 一气呵成 收货 Tool — R1 max + R2 context + R3 status dropdown + R4 5min 同 PO 幂等. ' ||
    '不同于 Sprint 8 receive_with_limit (创建 DRAFT 仍需 confirm), receive_confirm_create 直接 ' ||
    'createReceiveRecord → confirmReceive 串联, 立即更新库存 + 创建 MaterialBatch + 自动挂应付. ' ||
    '触发场景: 仓管员在 Workdesk 选某 PO 行点"确认收货", 或 AI 上下文识别"签收 PO-X N 件".',
    72,
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

-- V20260822_03__sprint10_loop1_shipment_intents.sql
--
-- Sprint 10 Loop 1 — 发货/出库 AI 闭环 (2026-05-21).
-- 注册 1 Workdesk-level intent + 1 Tool-level intent.
--
-- Workdesk intent (tool_name=NULL, SkillRegistry/AI 编排 + Sprint 9 P0.1 WORKDESK route):
--   SPRINT10_SHIPMENT_PENDING_TODAY → AI 编排 shipment_confirm_create tool (query 模式)
--
-- Tool intent (LLM 直接绑 tool_name 走 Tool 直接执行):
--   shipment_confirm_create → 双模式 Tool (query 列今日待发 / confirm 创发货单)
--
-- Sprint 9 hotfix pattern: NO `examples` column, ::jsonb cast for keywords,
-- single quoted literal in COMMENT (no `||`), idempotent ON CONFLICT.

-- ===== 1. Workdesk-level intent (highest priority, query 路由) =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    keywords, sensitivity_level, is_active, description, priority,
    created_at, updated_at
) VALUES
  -- Path A keyword examples: "今日 SO 待发" / "今天发什么" / "今天该出什么货"
  -- Path B LLM-routed synonym examples: "需要发哪些订单" / "待发清单"
  (gen_random_uuid(), 'SPRINT10_SHIPMENT_PENDING_TODAY', '今日待发清单', 'WORKDESK',
   'shipment_confirm_create',
   '["今日待发","今日 SO 待发","今天发什么","今天该出什么货","需要发哪些订单","待发清单","待发列表","今天发货","今日发货清单"]'::jsonb,
   'LOW', true,
   'Sprint 10 Loop 1 — AI 闭环发货入口. 列今日 status 可发货 + requiredDeliveryDate<=today + 未完全发出的销售订单.',
   50, NOW(), NOW()),

  (gen_random_uuid(), 'SHIPMENT_CONFIRM_CREATE', '确认发货创单', 'WRITE',
   'shipment_confirm_create',
   '["确认发货","创建发货单","生成发货单","出库","发货确认","1键发货","一键发货"]'::jsonb,
   'MEDIUM', true,
   'Sprint 10 Loop 1 — confirm 模式. 接 salesOrderId + items[{salesOrderItemId, actualQty}] 创发货单, R1 max + R2 context + R4 5min idempotent.',
   70, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    tool_name = EXCLUDED.tool_name,
    keywords = EXCLUDED.keywords,
    sensitivity_level = EXCLUDED.sensitivity_level,
    is_active = EXCLUDED.is_active,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    updated_at = NOW();

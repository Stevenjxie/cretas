-- V20260822_04__procurement_loop_intents.sql (was V_03 — bumped after Loop 1 PR #165 took V_03)
--
-- Sprint 10 Loop 3 — 采购下单 AI 闭环 (2026-05-21)
-- 注册 1 Tool-level intent (DATA_OP) — Workdesk 入口 PURCHASER_WEEKLY_PLAN
-- 已在 V20260820_09 注册, 此 migration 仅添加新 Tool intent + 扩展 STOCK_ALERT keywords.
--
-- Loop 3 闭环:
--   Trigger: 复用 STOCK_ALERT (低库存清单) — Path A "低库存物料" / Path B "什么物料不够了"
--   Confirm: 弹 PO Draft 对话框, 数量 max = 月均用量 × 3
--   Backend write: procurement_order_create Tool → 创 PurchaseOrder DRAFT
--   AI reply: "已生 PO-X 草稿 — 前往审批: /purchase/orders/{poId}"
--
-- Pattern mirrors V20260820_09 (REQUISITION_CREATE proven). ON CONFLICT DO UPDATE for idempotent re-run.
--
-- 3-strike preflight (per feedback_3_strike_comprehensive_audit_over_reactive_patching HARD):
--   1. NO `||` in COMMENT ON / column comments — PostgreSQL syntax disallow string concat in COMMENT
--   2. JSONB cast: keywords field uses native PostgreSQL JSONB literal (no ::jsonb cast needed for column type JSONB)
--   3. WORKDESK intent tool_name = NULL pattern — Loop 3 is Tool-level DATA_OP (NOT WORKDESK), so tool_name='procurement_order_create' set explicitly

-- ===== 1. Tool-level intent: PROCUREMENT_ORDER_CREATE =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'PROCUREMENT_ORDER_CREATE',
    '一键采购下单',
    'DATA_OP',
    'procurement_order_create',
    'MEDIUM',
    '["一键采购","采购下单","创建采购单","创建采购订单","新建采购单","下采购单","直接采购","快速采购","为我采购","帮我采购","生成采购单"]',
    'Sprint 10 Loop 3 — 一键采购下单 AI 闭环灵魂 Tool. Preview 显物料/数量 (max=月均×3)/推荐供应商/期望到货. WRITE + Preview — preview 用户确认后才创 PurchaseOrder DRAFT. 防呆 R1+R2+R3+R4: 数量上限 / 物料+供应商身份 / 供应商 dropdown ranked / 5min idempotent. 复用 PurchaseService.createPurchaseOrder.',
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

-- ===== 2. 扩展 STOCK_ALERT keywords =====
-- Path A "低库存物料" / "缺料" / "今天该补什么货"
-- Path B "什么物料不够了" / "需要采购啥"
-- 已在 V20260820_09 注册基础 keywords, 此处扩展支持 Loop 3 brief 中的双路径触发词

UPDATE ai_intent_configs
SET keywords = '["低库存","低库存物料","哪些料快没了","需要补的物料","缺料","缺料分析","库存不足","补货清单","安全库存","快没的料","今天该补什么货","什么物料不够了","需要采购啥","哪些物料缺货","补货建议","料缺了","料不够"]',
    description = 'Sprint 10 Loop 3 extended — 采购员低库存预警 + 推荐补货量 (缺口 × 1.5) + 推荐供应商 (last-PO + price ranked). 复用 MaterialBatchService.getLowStockWarnings. 支持按 materialCategory 过滤. read-only. Path A keyword match + Path B LLM-routed synonym 双路径触发.',
    updated_at = NOW()
WHERE intent_code = 'STOCK_ALERT';

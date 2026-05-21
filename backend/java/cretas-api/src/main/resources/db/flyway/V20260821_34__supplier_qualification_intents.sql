-- V20260821_34__supplier_qualification_intents.sql
--
-- Sprint 9 P2.C 食品行业法定 — 供应商食品资质过期预警 intent 注册 (2026-05-21).
-- 法定: 食品安全法第 51 条 + 供应商资质审核要求
--
-- 注册 1 Workdesk-level intent + 4 Tool-level intents:
--   SUPPLIER_QUALIFICATION_ALERT          → workdesk widget (无 tool_name, 关键词路由)
--   SUPPLIER_QUALIFICATION_CREATE         → supplier_qualification_create (WRITE + Preview MEDIUM)
--   SUPPLIER_QUALIFICATION_QUERY_EXPIRING → supplier_qualification_query_expiring (READ + FILTER)
--   SUPPLIER_QUALIFICATION_LOOKUP         → supplier_qualification_lookup (READ)
--   SUPPLIER_CAN_ORDER_CHECK              → supplier_can_order_check (READ + RULE — PO gate)
--
-- Pattern mirrors V20260820_07 (Sprint 8 P3 proven). ON CONFLICT DO UPDATE for idempotent re-run.

-- ===== 1. Workdesk-level intent (Sprint 9 P0.1 WORKDESK route active) =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'SUPPLIER_QUALIFICATION_ALERT', '供应商资质预警', 'WORKDESK',
     NULL, 'LOW',
     '["供应商资质预警","资质快过期","哪些供应商资质要过期","供应商证书到期","供应商证过期",' ||
     '"SC 证过期","HACCP 过期","ISO 22000 过期","进口商资质过期","供应商证书预警"]',
     'Sprint 9 P2.C 质量主管/采购经理 Workdesk 资质预警入口 — 列工厂内 30/7/0 天三级到期 ' ||
     '资质 (SC 证 / HACCP / 营业执照 / ISO 22000 等). ' ||
     'Cretas vs HJ 永久差异化护城河 — HJ 仅 Supplier.lastAuditDate 字段无主动预警/锁单.',
     40, true, NOW(), NOW())

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

-- ===== 2. 4 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'SUPPLIER_QUALIFICATION_CREATE', '登记供应商资质', 'DATA_OP',
     'supplier_qualification_create', 'MEDIUM',
     '["登记供应商资质","录入资质","登记 X 的 SC 证","添加 HACCP","录入营业执照",' ||
     '"添加供应商证书","新增资质","录入 SC 证","录入 ISO 22000","录入第三方检测"]',
     '登记供应商食品资质 (SC 证 / 进口商资质 / HACCP / 营业执照 / ISO 22000 / FSSC 22000 / ' ||
     '第三方检测). WRITE + Preview MEDIUM. 自动按 expiry_date 算初始 status.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'SUPPLIER_QUALIFICATION_QUERY_EXPIRING', '查询即将过期资质', 'QUERY',
     'supplier_qualification_query_expiring', 'LOW',
     '["哪些供应商资质快过期","供应商证书到期预警","7 天内过期的资质","30 天内过期",' ||
     '"过期的 SC 证有哪些","查到期资质","到期供应商证书","快过期的证书"]',
     '查询即将过期 (或已过期) 的供应商食品资质 — Workdesk 预警 widget 用. ' ||
     'read-only. 默认 30 天. 返三档: EXPIRED ⛔ / URGENT 🔴 (7天) / WARNING 🟡 (30天).',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'SUPPLIER_QUALIFICATION_LOOKUP', '查询供应商资质', 'QUERY',
     'supplier_qualification_lookup', 'LOW',
     '["查供应商 X 的资质","X 有没有 SC 证","X 的 HACCP 在哪","查这家供应商所有证书",' ||
     '"供应商资质列表","X 的资质","供应商证书查询"]',
     '查询指定供应商的全部食品资质 — 按 supplierId + 可选 qualificationType filter. ' ||
     'read-only. 返全部状态 (VALID/EXPIRING/EXPIRED/REVOKED).',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'SUPPLIER_CAN_ORDER_CHECK', '供应商下单 gate 检查', 'QUERY',
     'supplier_can_order_check', 'LOW',
     '["X 能下单吗","X 资质完整吗","给 X 下采购单可以吗","X 还能进货吗",' ||
     '"X 的强制资质完整吗","下采购单前检查","供应商下单 gate","供应商资质 gate"]',
     '供应商下单前资质检查 (PO BLOCKING gate) — 强制资质 (SC 证 + 营业执照) ' ||
     'VALID/EXPIRING 才能下单. 返 canOrder=true/false + reason. read-only. PO 创建前 gate.',
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

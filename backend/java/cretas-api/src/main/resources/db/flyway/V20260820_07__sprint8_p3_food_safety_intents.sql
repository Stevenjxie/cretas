-- V20260820_07__sprint8_p3_food_safety_intents.sql
--
-- Sprint 8 P3 — 质量主管 Workdesk 食品安全召回闭环 (2026-05-20).
-- 注册 3 Skill 顶层 intent + 8 Tool-level intents.
--
-- Skill intents (tool_name=NULL, SkillRegistry 关键词路由):
--   FOOD_SAFETY_RECALL    → food-safety-recall Skill (8 Tool 编排)
--   HACCP_CHECKPOINT      → haccp-checkpoint-management Skill
--   ADDITIVE_COMPLIANCE   → food-additive-compliance Skill
--
-- 8 Tool-level intents (LLM 直接绑 tool_name 走 Tool 直接执行):
--   BATCH_TRACE_BY_CUSTOMER  → batch_trace_by_customer_date
--   BATCH_FULL_TRACE         → batch_full_trace
--   HACCP_REVIEW             → haccp_checkpoint_review
--   ADDITIVE_CHECK           → additive_compliance_check
--   INVENTORY_FREEZE         → inventory_freeze (WRITE + Preview BLOCKING)
--   CUSTOMER_NOTIFY_BATCH    → customer_notify_batch (WRITE + Preview)
--   REGULATORY_REPORT        → regulatory_report_generate (GENERATE)
--   RECALL_LOSS_ESTIMATE     → recall_loss_estimate
--
-- Pattern mirrors V20260820_01/02 (P1/P2 proven). ON CONFLICT DO UPDATE for idempotent re-run.

-- ===== 1. 3 Skill-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'FOOD_SAFETY_RECALL', '食品安全召回', 'WORKDESK',
     NULL, 'HIGH',
     '["启动召回","食品召回","拉肚子","客户投诉","出问题","召回 X 批次","召回 B-","召回流程","食品安全事件","需要召回","客户中毒","客户不舒服","食源性"]',
     'Sprint 8 P3 质量主管 Workdesk 入口 — 串 8 Tool 完成召回 6 步闭环 (追溯/HACCP/添加剂/冻结/通知/上报/损失). ' ||
     '触发 food-safety-recall Skill (定义于 SkillRegistryImpl.initializeDefaultSkills). ' ||
     'Cretas vs HJ 永久差异化护城河 — HJ 0 食品行业垂直能力.',
     40, true, NOW(), NOW()),

    (gen_random_uuid(), 'HACCP_CHECKPOINT', 'HACCP 检查', 'QUERY',
     NULL, 'LOW',
     '["HACCP","CCP","危害控制","关键控制点","X 批次 HACCP","HACCP 通过","今天 CCP 监控","查 CCP 监控","HACCP 记录"]',
     'Sprint 8 P3 HACCP 检查 — haccp-checkpoint-management Skill. ' ||
     '查指定批次 CCP 全记录 + 偏离告警 + correctiveAction. read-only.',
     60, true, NOW(), NOW()),

    (gen_random_uuid(), 'ADDITIVE_COMPLIANCE', '添加剂合规检查', 'QUERY',
     NULL, 'LOW',
     '["添加剂","GB 2760","合规","限量","亚硝酸盐","防腐剂","X 批次添加剂超限了吗","营养标签","添加剂校验","合规标签","GB 2760 合规"]',
     'Sprint 8 P3 GB 2760-2014 食品添加剂合规校验 — food-additive-compliance Skill. ' ||
     '查 batch ingredients 限量 + 标记超限 + 营养标签草稿. read-only.',
     60, true, NOW(), NOW())

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
    (gen_random_uuid(), 'BATCH_TRACE_BY_CUSTOMER', '按客户日期追溯批次', 'QUERY',
     'batch_trace_by_customer_date', 'LOW',
     '["追溯","哪批","客户那批","X 客户买的","按客户日期","上周三的货是哪批","客户那天收到的是哪批"]',
     '按客户 + 投诉日期反查产品批次 (食品召回首步). 通过 ShipmentRecord 反查 batch_number. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'BATCH_FULL_TRACE', '批次完整追溯', 'QUERY',
     'batch_full_trace', 'LOW',
     '["完整追溯","批次详情","原料来源","出货客户","B- 全追溯","这批用了哪些原料卖给了谁","批次追踪"]',
     '批次完整追溯 — 原料 + 供应商 + 加工记录 + 受影响客户列表. 食品召回闭环 step 2. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'HACCP_REVIEW', 'HACCP 监控审查', 'QUERY',
     'haccp_checkpoint_review', 'LOW',
     '["HACCP 监控","CCP 记录","偏离","B- 的 HACCP","HACCP 检查"]',
     'HACCP CCP 审查 — 查指定批次的全部监控记录 + 标记 deviation + correctiveAction. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'ADDITIVE_CHECK', '添加剂限量校验', 'QUERY',
     'additive_compliance_check', 'LOW',
     '["添加剂校验","GB 2760 校验","超限","添加剂限量","B- 添加剂"]',
     'GB 2760-2014 添加剂限量校验 — 查 ingredients 跟 max_limit 比较, 标记超限. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'INVENTORY_FREEZE', '冻结库存', 'DATA_OP',
     'inventory_freeze', 'HIGH',
     '["冻结","库存冻结","封存","冻结 B-","召回先冻结库存","这批不许再发货"]',
     '冻结指定批次库存 (status → FROZEN). WRITE + Preview BLOCKING. 召回 SOP 第一步.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'CUSTOMER_NOTIFY_BATCH', '批量通知客户', 'DATA_OP',
     'customer_notify_batch', 'MEDIUM',
     '["通知客户","召回通知","发短信","通知所有买过","群发召回短信","召回通知客户"]',
     '批量通知客户召回事件 (基于 ShipmentRecord 反查影响客户). WRITE + Preview. ' ||
     'NotificationService 推送 + RecallAction audit.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'REGULATORY_REPORT', '生成监管上报文件', 'DATA_OP',
     'regulatory_report_generate', 'MEDIUM',
     '["监管上报","上报文件","食药监","召回报告","监管文件","生成召回上报文件"]',
     '生成监管上报文件 (markdown report + PDF link). 召回 SOP 监管上报 step. RecallEvent → REPORTED.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'RECALL_LOSS_ESTIMATE', '召回损失预估', 'ANALYSIS',
     'recall_loss_estimate', 'LOW',
     '["损失预估","召回成本","损失多少","召回会赔多少","召回损失","召回成本预估"]',
     '召回损失预估 — 聚合冻结库存 + 退货 + 行政 + 品牌损失. 更新 RecallEvent.estimatedLoss. read-only.',
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

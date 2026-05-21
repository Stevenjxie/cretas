-- V20260820_09__sprint8_p4b_purchaser_intents.sql
--
-- Sprint 8 P4b — 采购员 Workdesk (2026-05-20)
-- 注册 1 Workdesk 顶层 intent + 5 Tool-level intents.
--
-- Workdesk 触发 (Workdesk auto-trigger entry point):
--   PURCHASER_WEEKLY_PLAN → stock_alert Tool
--   (单 Tool 走 Tool 直接执行, Sprint 9 升级 Skill 编排串 5 Tool 输出"5 品类预警")
--
-- 5 Tool intents (LLM 绑 tool_name 走 Tool 直接执行分支):
--   STOCK_ALERT             → stock_alert             (READ — 低库存预警 + 推荐补货量)
--   SALES_FORECAST_7DAY     → sales_forecast_7day     (READ — 简单线性预测, 不上 ML)
--   SUPPLIER_DELIVERY_ETA   → supplier_delivery_eta   (READ — supplier.deliveryDays + 历史 AVG)
--   PRICE_HISTORY_QUERY     → price_history_query     (READ — 月度加权 AVG + 同比)
--   REQUISITION_CREATE      → requisition_create      (WRITE + Preview — 灵魂 Tool, R1+R2+R4)
--
-- 客户原话证据 (F006 张权-昆山): 采购员小赵场景 "下周采购什么?" — 触发关键词强调
-- "下周 / 采购 / 补货 / 缺料 / 价格 / 供应商 / 请购".
--
-- Pattern mirrors V20260820_01/02/07/08 (P1/P2/P3/P4a proven). ON CONFLICT DO UPDATE for idempotent re-run.

-- ===== 1. Workdesk-level entry intent =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'PURCHASER_WEEKLY_PLAN',
    '采购员周计划',
    'WORKDESK',
    'stock_alert',  -- 默认走 stock_alert (Sprint 9 改 Skill 编排聚合 5 Tool "5 品类预警")
    'LOW',
    '["下周采购什么","下周采购","采购计划","采购员工作台","本周采购","下周买什么","下周该采购什么","下周需要采什么","采购员任务","本周采购计划","下周补货","下周缺什么"]',
    'Sprint 8 P4b 采购员 Workdesk 入口 — 当前 default 走 stock_alert Tool 输出低库存清单 + 补货建议. ' ||
    '采购员小赵场景 (F006): "下周采购什么? 系统直接告诉我". ' ||
    'Cretas vs HJ 防呆护城河 — HJ 4 菜单 + 手工算交期 vs Cretas 1 屏 30 秒 AI 输出 + 一键请购.',
    45,
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

-- ===== 2. 5 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'STOCK_ALERT', '低库存预警 (采购员)', 'QUERY',
     'stock_alert', 'LOW',
     '["低库存","哪些料快没了","需要补的物料","缺料分析","库存不足","补货清单","安全库存","快没的料"]',
     '采购员低库存预警 — 复用 MaterialBatchService.getLowStockWarnings + 推荐补货量 (缺口 × 1.5). ' ||
     '支持按 materialCategory 过滤. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'SALES_FORECAST_7DAY', '7 天销售预测', 'ANALYSIS',
     'sales_forecast_7day', 'LOW',
     '["下周销量","7 天销售预测","下周大概卖多少","未来一周需求","下周哪些产品好卖","销量预测","下周需求量"]',
     '简单线性预测 — 最近 14 天 SO history AVG × 7 × 1.1 buffer (不上 ML, Sprint 9 升级 SmartBI LightGBM). ' ||
     'read-only. Top 20 产品.',
     75, true, NOW(), NOW()),

    (gen_random_uuid(), 'SUPPLIER_DELIVERY_ETA', '供应商交期 ETA', 'QUERY',
     'supplier_delivery_eta', 'LOW',
     '["供应商交期","几天能到货","X 供应商交期多久","供应商交期靠谱不","历史平均交期","到货时间"]',
     '综合 supplier.deliveryDays + 最近 90 天历史 PO 平均 (实收日-下单日). read-only. ' ||
     '可选按 supplierId / materialTypeId 过滤.',
     75, true, NOW(), NOW()),

    (gen_random_uuid(), 'PRICE_HISTORY_QUERY', '价格历史查询', 'QUERY',
     'price_history_query', 'LOW',
     '["历史价格","报价合理吗","涨价了多少","价格曲线","采购价对比","历史采购价","均价"]',
     '按 materialTypeId 输出最近 N 个月加权 AVG + 同比趋势. read-only. ' ||
     '受 PriceSensitive 影响, 仓管员角色看不到 unitPrice.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'REQUISITION_CREATE', '一键创建请购单', 'DATA_OP',
     'requisition_create', 'MEDIUM',
     '["创建请购单","我要请购","建议补货","缺料请购","申请采购","写请购单","下请购单","补货申请"]',
     '一键创建请购单 (DRAFT). preview 显物料/数量/预算/供应商/审批 chain. ' ||
     'WRITE + Preview — preview 用户确认后才真创建. 防呆 R1+R2+R4 灵魂 Tool. ' ||
     '复用 PurchaseRequisitionService.createRequisition (Sprint 5 D ship).',
     70, true, NOW(), NOW())

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

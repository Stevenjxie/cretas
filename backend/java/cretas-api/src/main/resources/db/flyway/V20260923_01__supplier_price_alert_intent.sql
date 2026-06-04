-- 供应商价格预警 AI 意图绑定 → supplier_price_alert Tool (邓总点名痛点, 复用 #53)
--
-- 背景: Wave2 #53 价格异常威慑引擎已上线 (detect_price_anomalies + web-admin 价格异常看板),
--   但只有看板入口, 没有 AI 问答入口。邓总要"哪个供应商涨价了"能在「智能问答」直接问,
--   基准锁定为<b>各食材自身 90 天移动均价</b> (#53 detector 的 baseline_mode=days 模式)。
--
-- 本迁移: 新建 SUPPLIER_PRICE_ALERT 意图绑定到 supplier_price_alert Tool
--   (该 Tool 调 GoldFinanceClient.fetchPriceAnomalies(factoryId,"days",90,5.0) → #53 detect 端点)。
--   不新建 detector / 不新建数据表 — 纯复用。
--
-- 路由: priority 115 (> 默认 85/90), 强关键词命中 KEYWORD 层, 直接 Tool 执行不 fallthrough LLM。
--   business_type='RESTAURANT' — 邓总是餐饮客户, 进价数据来自餐厅送货单 (agg_supplier_price)。
-- 敏感级 LOW: 只读预警 (威慑非处罚)。绝对价格的 RBAC 在 Python detect 端点按角色 strip,
--   不在意图层 — 偏离率/方向/风险等级对所有角色可见。
--
-- flyway 版本 20260923.01 > Java flyway 当前 max 20260922.04 (out-of-order=false, 必须更大)。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
--
-- keywords 列在 prod 是 json/jsonb (ddl-auto columnDefinition="JSON"), 历史教训需 ::jsonb cast
-- (V20260903_01 等同款), 否则 fresh-CI text 列下 jsonb 函数报错。本迁移只 INSERT 字面量, 用 ::jsonb 显式标注。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'SUPPLIER_PRICE_ALERT', '供应商价格预警', 'SMARTBI', 'supplier_price_alert', 'LOW',
        '["哪个供应商涨价了","供应商涨价","供应商涨价预警","哪些食材涨价","食材涨价超标","进价异常","哪个供应商涨价","谁涨价了","供应商价格预警","价格预警","进价预警","哪个供应商价格不对","最近进价异常","食材涨价预警","供应商加价","谁的进价不对劲"]'::jsonb,
        '供应商价格预警: 基于各食材近 90 天移动均价, 找出最近进价异常偏离的供应商×食材并按风险排序 (复用价格异常威慑引擎 #53)。绝对价格按采购价格权限脱敏, 涨幅/风险对所有角色可见。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'supplier_price_alert',
    intent_category = 'SMARTBI',
    sensitivity_level = 'LOW',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

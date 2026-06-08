-- GAP-06-C (QA 组5) — 餐饮供应商应付账款查询意图 → 工具绑定
--   RESTAURANT_ARAP_PAYABLE_QUERY → restaurant_arap_payable_query
--
-- 问题: 餐饮租户问 "应付多少" → 被路由到营销员提成工具 (RESTAURANT_REP_COMMISSION_QUERY),
--       返回佣金应付 ¥0.00, 而非供应商应付账款 (ArApTransaction AP 余额)。
--
-- 修法: 注册专用意图, 绑定 RestaurantArApPayableTool (查询 ArApTransactionRepository.sumPayables)。
--       IntentKnowledgeBase.restaurantPhraseMapping 确定性短语路由保证 "应付多少 / 应付账款 / 欠款"
--       直接命中本意图, 不落语义评分 → 不与提成工具混淆。
--
-- business_type='RESTAURANT': 制造业工厂不进此意图 (工厂有单独的 ACCOUNTS_RECEIVABLE_AGING)。
-- priority 115: 与同期餐饮意图对齐。
-- sensitivity_level=LOW: 只读查询; 金额 @PriceSensitive 由 PriceFieldResponseAdvice 按角色脱敏。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'RESTAURANT_ARAP_PAYABLE_QUERY',
    '供应商应付账款查询',
    'SMARTBI',
    'restaurant_arap_payable_query',
    'LOW',
    '["应付多少","应付账款","欠款多少","供应商应付","需要付多少","要付多少钱","还欠多少","未付款多少","应付供应商多少","欠供应商多少","应付余额","待付款"]'::jsonb,
    '查询餐厅供应商应付账款余额（AP挂账净额）及最近AP明细。' ||
    '适用 "应付多少 / 应付账款 / 欠款多少 / 供应商应付" 类问题。' ||
    '区别于营销员提成应付（提成类问题请用 RESTAURANT_REP_COMMISSION_QUERY）。',
    115,
    true,
    'RESTAURANT',
    NOW(),
    NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name       = 'restaurant_arap_payable_query',
    intent_category = 'SMARTBI',
    keywords        = EXCLUDED.keywords,
    description     = EXCLUDED.description,
    priority        = 115,
    is_active       = true,
    business_type   = 'RESTAURANT',
    updated_at      = NOW();

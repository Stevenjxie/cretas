-- SP6 采购到付款 — 出纳/财务 AI 意图绑定
-- 两个意图: 付款申请查询 + 付款请求审批
-- 幂等: ON CONFLICT (intent_code) DO UPDATE

-- 1. 付款申请查询（出纳台账）
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'PAYMENT_REQUEST_QUERY',
    '付款申请查询',
    'PROCUREMENT',
    'payment_request_query',
    'LOW',
    '["付款申请","待付款","已审批付款","出纳台账","需要付款","应付款项","付款列表","付款审批","未付款申请"]'::jsonb,
    '查询采购付款申请列表（出纳台账）。' ||
    '返回 APPROVED 状态待出纳付款的申请，按审批时间升序排列。' ||
    '适用 "待付款 / 付款申请 / 出纳台账" 类问题。',
    100,
    true,
    'FACTORY',
    NOW(),
    NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name       = 'payment_request_query',
    intent_category = 'PROCUREMENT',
    keywords        = EXCLUDED.keywords,
    description     = EXCLUDED.description,
    priority        = 100,
    is_active       = true,
    updated_at      = NOW();

-- 2. 采购异常单查询
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    business_type, created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'PURCHASE_EXCEPTION_QUERY',
    '采购异常单查询',
    'PROCUREMENT',
    'purchase_exception_query',
    'LOW',
    '["采购异常","超收","少收","收货异常","入库异常","异常单","收货不符","数量不对","采购差异"]'::jsonb,
    '查询采购收货异常单（超收/少收）。' ||
    '返回工厂的采购异常记录，可按状态过滤（PENDING 待处理 / RESOLVED 已处理）。' ||
    '适用 "超收了 / 少收了 / 收货异常 / 采购差异" 类问题。',
    100,
    true,
    'FACTORY',
    NOW(),
    NOW()
)
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name       = 'purchase_exception_query',
    intent_category = 'PROCUREMENT',
    keywords        = EXCLUDED.keywords,
    description     = EXCLUDED.description,
    priority        = 100,
    is_active       = true,
    updated_at      = NOW();

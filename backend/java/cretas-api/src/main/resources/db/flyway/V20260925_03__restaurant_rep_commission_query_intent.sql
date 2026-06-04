-- #59 Phase 2 — 营销员提成查询意图 → 工具绑定 (cretas_db)
--   RESTAURANT_REP_COMMISSION_QUERY → restaurant_rep_commission_query (我的提成 / 本月提成多少)
--
-- flyway 版本 20260925.03 (Phase 2 块 _01 表 / _02 表 / _03 意图).
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行.
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图).
-- keywords ::jsonb cast 与既有迁移一致 (keywords 列跨环境 text/json/jsonb 不一致).
-- priority 115 与同期餐饮意图对齐.
-- sensitivity_level=LOW: 读路径不写库; 提成金额在工具内按财务/价权角色 + 本人门控, 无权限只显业绩档位不泄金额.
-- ⚠️ 关键词避开"怎么样"形式 (已知歧义查询局限).

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REP_COMMISSION_QUERY', '营销员提成查询', 'SMARTBI', 'restaurant_rep_commission_query', 'LOW',
        '["我的提成","本月提成","我这个月提成","营销员提成","提成多少","我的提成多少","本月提成多少","这个月提成","我的业绩提成","提成查询","当月提成"]'::jsonb,
        '营销员月度阶梯提成查询: 按月度累计复购业绩返回当月累计业绩、所处档位、累计提成金额与计业绩到访次数。' ||
        '提成金额仅财务/价格查看权限角色或营销员本人可见, 其余角色只显业绩与档位不显金额。' ||
        '适用 "我的提成 / 本月提成多少 / 营销员提成" 类问题。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_rep_commission_query',
    intent_category = 'SMARTBI',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

-- #56 价值可视化回馈回路 — RESTAURANT_VALUE_SUMMARY 意图 → restaurant_value_summary 工具
-- (2026-06-04)
--
-- 背景: 诊断引擎已算出省钱/改善金额 (人工刚性节省/食材改善空间/档口损溢/处方预估),
--   但门店看不到价值 → 配合度崩塌 (金毛范囊死因)。本意图让客户问 "本月省了多少 /
--   价值回馈 / 帮我配合系统能省多少钱" 时, 命中 RestaurantValueSummaryTool 读取
--   restaurant_value_snapshots 最新快照, 返回月度+年化两口径 (D3)。
--
-- flyway 版本 20260919.01: origin/main 已应用 max 20260917.02 (out-of-order=false,
--   必须更大)。Wave2 #54 占 V20260918_01, #56 隔离上移取 19 避免冲突。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图)。
-- keywords ::jsonb cast 与既有迁移一致 (keywords 列跨环境 text/json/jsonb 不一致,
--   jsonb 入参必须显式 cast)。
-- ⚠️ 关键词避开"怎么样"形式 (已知歧义查询局限)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_VALUE_SUMMARY', '价值回馈月报', 'SMARTBI', 'restaurant_value_summary', 'LOW',
        '["本月省了多少","价值回馈","能省多少","节省了多少","可优化空间","本月价值","省钱","可节省金额","配合系统省钱","经营改善价值","本月可省","价值月报","降本空间","优化空间有多少"]'::jsonb,
        '价值回馈月报 (restaurant_value_snapshots 快照): 把诊断引擎已算出的省钱/改善金额按月度+年化两口径呈现给门店经理/老板 — 人工刚性节省、食材成本改善空间、折扣率改善空间、档口损溢超标、处方预估收益, 区分预估/实测。适用 "本月省了多少 / 价值回馈 / 可优化空间 / 降本空间" 类问题。',
        110, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_value_summary',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 110,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

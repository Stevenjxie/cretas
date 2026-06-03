-- 餐饮营收趋势/同比环比意图 → restaurant_revenue_trend_gold 工具 (qhj, 2026-06-03)
--
-- 背景 (prod 验证): AI问答 (/ai-intents/execute) 里 "趋势/同比/环比/增长下降" 类问题路由错:
--   "同比和环比分析,识别增长和下降趋势" → COMPREHENSIVE_SYNTHESIS (priority 108,
--     tool=restaurant_comprehensive_synthesis_gold) → 综合分析的 FactBook 没有月度时间序列,
--     答出 "无法计算同比环比/趋势" (综合分析做不了月度趋势)。
--   "月度趋势"/"销售额的月度变化趋势" → RESTAURANT_REVENUE_TREND (priority 100, tool=NULL)
--     → LLM 兜底空话, 不出真数据。
--   "营收趋势" → RESTAURANT_DAILY_REVENUE (priority 100, tool=NULL) → FAILED 死胡同。
--   而 qhj 在 gold 层有完整 2025+2026 POS 月度营收 (trend-bundle 端点已返 monthlyTrend)。
--
-- 修法: 新建 gold 工具 restaurant_revenue_trend_gold (RestaurantRevenueTrendGoldTool, 经
--   GoldFinanceClient.fetchTrendBundle → /api/smartbi/gold/trend-bundle) 给出月度营收趋势+
--   峰谷+首尾环比+周末周中, 并出月度折线图。
--   - RESTAURANT_REVENUE_TREND.tool_name = 'restaurant_revenue_trend_gold', priority 115。
--     priority 115 > COMPREHENSIVE_SYNTHESIS 的 108, 让 "同比环比/趋势" 问题命中本意图而非综合分析。
--   - RESTAURANT_DAILY_REVENUE 也改绑同一工具 (它当前 tool=NULL/FAILED, 指向趋势工具是严格改进;
--     "营收趋势/日营收" 同走真数据折线趋势, 优于死胡同)。
--   ⚠️ 不动 COMPREHENSIVE_SYNTHESIS 的绑定 — 真正的 "综合/整体/多维分析" (无趋势/同比/环比关键词)
--     仍走 priority 108 综合分析意图; 趋势词不与综合分析关键词重叠, 路由清晰。
--
-- flyway 版本 20260912.01 > origin/main 已应用 max 20260911.01 (out-of-order=false, 必须更大)。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图)。
-- keywords ::jsonb cast 与 V20260907_01 / V20260910_01 一致 (keywords 列跨环境
--   text/json/jsonb 不一致, jsonb 入参必须显式 cast, 否则 fresh-CI DB 报类型错误)。
-- ⚠️ 关键词避开"怎么样"形式 (已知歧义查询局限 — 以"怎么样"结尾会被判 GENERAL_QUESTION)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_REVENUE_TREND', '营收趋势分析', 'SMARTBI', 'restaurant_revenue_trend_gold', 'LOW',
        '["同比","环比","营收趋势","月度趋势","销售趋势","增长趋势","增长和下降","月度变化","走势","趋势分析","识别增长和下降趋势","同比和环比分析","销售额的月度变化趋势","营业额趋势","营收走势"]'::jsonb,
        '营收趋势/同比环比分析 (gold 层 trend_bundle 月度营收序列): 月度营收趋势+峰值月/最低月+首尾环比方向+周末周中对比+月度折线图。适用趋势/同比/环比/增长和下降/月度变化/走势分析。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_revenue_trend_gold',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

-- 把 tool=NULL/FAILED 的 RESTAURANT_DAILY_REVENUE 也指向趋势工具 (严格改进, 不再死胡同)。
INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_DAILY_REVENUE', '日营收/营收趋势', 'SMARTBI', 'restaurant_revenue_trend_gold', 'LOW',
        '["营收趋势","日营收","每日营收","营业额趋势","收入趋势"]'::jsonb,
        '营收趋势 (gold 层 trend_bundle): 月度营收趋势+峰谷+环比+折线图。原 tool=NULL 死胡同, 改绑真数据趋势工具。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_revenue_trend_gold',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

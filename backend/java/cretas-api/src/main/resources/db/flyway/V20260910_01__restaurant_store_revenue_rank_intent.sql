-- 餐饮门店营收排行意图 → restaurant_store_revenue_rank_gold 工具 (qhj, 2026-06-02)
--
-- 背景 (prod 验证扫描发现): qhj 仪表盘高频 chip "哪家店业绩最好 / 门店营收对比 / 门店业绩排名"
--   路由到坏意图 — "门店营收对比" → RESTAURANT_DAILY_REVENUE (tool_name=NULL, FAILED 死胡同);
--   "哪家店业绩最好" → RESTAURANT_STORE_KPI_DASHBOARD (qhj 无 KPI 维度数据 → 诚实降级 "无数据")。
--   而 gold 工具 restaurant_store_revenue_rank_gold (RestaurantStoreRevenueRankGoldTool, 经
--   GoldFinanceClient.fetchFinanceSummary → gold agg_daily) 本就能答 "门店营收排行", 只是它绑在
--   RESTAURANT_OPS_STORE_MARGIN 上, 而该意图触发 3 选 1 澄清 (门店毛利/SKU毛利/菜品毛利), 这些直接
--   问句拿不到干净答案。
--
-- 修法: 建专用意图 RESTAURANT_STORE_REVENUE_RANK 直接绑 restaurant_store_revenue_rank_gold
--   (无澄清), 配合 IntentKnowledgeBase 短语短路把门店排行问句确定性路由到此意图。tool_name 多意图
--   共用同一 gold 工具是允许的 (OPS_STORE_MARGIN 保持不变)。
--
-- 范围: 仅 "最好/排行/对比/最赚钱" 类 (工具取营收 top, 不支持 "最差/垫底" 方向, 故不收 "最差"
--   关键词, 避免把 top 当 bottom 答错; "哪家店业绩最差" 维持现状不误导)。
--
-- flyway 版本 20260910.01 (原 20260909.01 与 #424 work_process_standard_hourly_rate 撞号,
--   并发 merge 后 origin/main 出现两个 V20260909_01 → 重编号到空号 20260910.01; 本迁移幂等未 apply 过, 改号安全)。
-- 幂等 ON CONFLICT (intent_code) DO UPDATE。priority 115 (= 经营基础问), business_type='RESTAURANT'
-- 业态门控放行。keywords ::jsonb cast 与 V20260903/04/07 一致 (跨环境 keywords 列类型不一, 须显式 cast)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_STORE_REVENUE_RANK', '门店营收排行', 'SMARTBI', 'restaurant_store_revenue_rank_gold', 'LOW',
        '["哪家店业绩最好","门店营收对比","门店营收排行","门店营收排名","门店业绩排名","哪家店最赚钱","哪家店营收最高","各门店营收对比","哪家店生意最好","门店销售排行","哪个店营收最高","门店收入排名"]'::jsonb,
        '门店营收排行 (哪家店业绩最好/门店排名/最赚钱的店, gold 层 agg_daily 按门店汇总营收 top)。适用连锁多门店营收对比。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_store_revenue_rank_gold',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

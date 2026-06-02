-- P3 门店评分×营收 跨数据集分析意图 → store_review_revenue 工具 (qhj, 2026-06-02)
--
-- 背景: P3 把评价门店名 (大众点评) 经 dim_store_review_alias 桥映射到 gold dim_store,
--   join 该店"评价聚合评分" × "gold 营收" 做相关性。诚实标注: 仅已确认/高置信映射进 join,
--   必返未关联门店名单, 关联门店 <4 家不给相关性结论。
--   工具 store_review_revenue (StoreReviewRevenueTool) 经 GoldFinanceClient.fetchStoreReviewRevenue
--   (转发 X-User-Role, 营收对非 price-view 角色剥零) → Python /api/smartbi/gold/store-review-revenue。
--
-- flyway 版本 20260907.01 > db/flyway 已应用 max 20260906.04 (out-of-order=false, 必须更大)。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- priority 115 (= 评价/经营基础问), business_type='RESTAURANT' 让业态门控放行。
-- keywords ::jsonb cast 与 V20260903_01 / V20260904_01 一致 (keywords 列跨环境 text/json/jsonb
--   不一致, jsonb 入参须显式 cast, 否则 fresh-CI DB 报类型错误)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_RATING_REVENUE_CORRELATION', '门店评分营收关联', 'SMARTBI', 'store_review_revenue', 'LOW',
        '["评分高的店是不是更赚钱","评分高的店更赚钱吗","门店评分和营收","口碑和营收的关系","评分与营收相关性","高分店赚钱吗","评分和赚钱有关系吗","哪个店又叫好又赚钱","门店口碑和业绩","评分高的门店营收","评价好的店营收高吗","口碑好的店赚钱吗"]'::jsonb,
        '门店评分×营收关联 (大众点评评分 × POS营收, 经门店别名桥)。仅含已确认门店映射, 诚实标注未关联门店, 不编造; 关联门店<4家不给相关性结论。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'store_review_revenue',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();

-- 餐厅经营驾驶舱 8 销售问答 → gold-backed Tool 意图绑定 (方案1, 2026-06-01)
--
-- 背景: 经营驾驶舱 8 个销售快捷问答在 Java 意图层全失败/误路由:
--   (A) 已正确识别但 tool_name 为空 → "暂未配置执行器":
--       RESTAURANT_BESTSELLER_QUERY / RESTAURANT_OPS_STORE_MARGIN / RESTAURANT_ORDER_STATISTICS
--   (B) 误识别到制造业意图 (语义/LLM 层):
--       员工里谁最厉害→HR_LEAVE_REQUEST_QUERY / 周末周中→SHIPMENT_STATS /
--       峰值月份→PROCESS_TASK_ANALYSIS / 优惠券→CUSTOMER_ACTIVE
--
-- 修法:
--   (A) UPDATE tool_name → 新 gold Tool + 补强关键词 (含驾驶舱原话, 让 KEYWORD 层命中)
--   (B) 新建强关键词 RESTAURANT 意图 (含驾驶舱原话 + autocomplete 变体)。
--       识别优先级 EXACT > PHRASE > KEYWORD > SEMANTIC, 所以精确短语关键词命中 KEYWORD 层,
--       压过原先 SEMANTIC/LLM 的误路由; business_type='RESTAURANT' 让业态门控对餐厅放行。
--   priority 90 (高于默认 85) 进一步保证餐厅意图在打平时胜出。
--   全部走 gold 层 (agg_product/agg_daily/agg_channel/agg_discount = canonical 数据),
--   不再 fallthrough 到不可靠的单上传文件聊天。
--
-- flyway 版本 20260902.01 > prod 已应用 max 20260901.03 (out-of-order=false, 必须更大)。
-- 幂等: ON CONFLICT (intent_code) DO UPDATE; UPDATE 用 WHERE intent_code, 可重复执行。

-- ============ (A) 已识别意图: 绑定 gold Tool + 补强关键词 ============

UPDATE ai_intent_configs
   SET tool_name = 'restaurant_dish_bestseller_gold',
       business_type = 'RESTAURANT',
       is_active = true,
       keywords = '["畅销品 Top 5","畅销品","畅销品排行","热销菜品","爆款菜品","卖得最好的菜","畅销菜","热销","销量最高"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_BESTSELLER_QUERY';

UPDATE ai_intent_configs
   SET tool_name = 'restaurant_store_revenue_rank_gold',
       business_type = 'RESTAURANT',
       is_active = true,
       keywords = '["哪家店业绩最好","门店业绩排名","哪家店最赚钱","门店营收对比","业绩冠军","哪家店业绩最差","门店","分店","店铺","哪家","连锁"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_OPS_STORE_MARGIN';

UPDATE ai_intent_configs
   SET tool_name = 'restaurant_order_type_mix_gold',
       business_type = 'RESTAURANT',
       is_active = true,
       keywords = '["外卖占比","外卖占比多少","堂食外卖对比","堂食外卖","外卖比例","外卖生意","堂食占比"]'::jsonb,
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_ORDER_STATISTICS';

-- ============ (B) 新建 gold 分析意图 (慢销 + 误路由的 4 个) ============

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_DISH_SLOW', '滞销菜品分析', 'SMARTBI', 'restaurant_dish_slowseller_gold', 'LOW',
        '["慢销菜品","慢销","滞销菜品","滞销","哪些菜卖不出去","销量垫底","卖不动","卖得最差","滞销菜"]'::jsonb,
        '查询销量垫底的滞销菜品 (gold 层 agg_product 升序)', 90, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_dish_slowseller_gold', keywords = EXCLUDED.keywords, business_type = 'RESTAURANT', priority = 90, is_active = true, updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_PEAK_MONTH', '营收峰值月份', 'SMARTBI', 'restaurant_peak_month_gold', 'LOW',
        '["峰值月份","营收最高的月份","哪个月营业额最高","月度峰值","最旺的月份","旺季月份","哪个月生意最好"]'::jsonb,
        '按月聚合营收, 找出峰值月份 (gold 层 daily-trend)', 90, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_peak_month_gold', keywords = EXCLUDED.keywords, business_type = 'RESTAURANT', priority = 90, is_active = true, updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_WEEKDAY_WEEKEND', '周末周中对比', 'SMARTBI', 'restaurant_weekday_weekend_gold', 'LOW',
        '["周末周中","周末周中对比","周末生意好还是平日","礼拜几卖得最好","周末工作日对比","周末平日","周末和周中"]'::jsonb,
        '对比周末 vs 周中日均营收 (gold 层 daily-trend 按星期聚合)', 90, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_weekday_weekend_gold', keywords = EXCLUDED.keywords, business_type = 'RESTAURANT', priority = 90, is_active = true, updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_DISCOUNT_USAGE', '优惠券使用情况', 'SMARTBI', 'restaurant_discount_usage_gold', 'LOW',
        '["优惠券使用情况","优惠券","优惠券使用","折扣率","代金券","满减","优惠使用","套餐券","团购券"]'::jsonb,
        '优惠券/折扣使用排行 (gold 层 discount-breakdown)', 90, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_discount_usage_gold', keywords = EXCLUDED.keywords, business_type = 'RESTAURANT', priority = 90, is_active = true, updated_at = NOW();

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_STAFF_RANKING', '员工开单排行', 'SMARTBI', 'restaurant_staff_ranking_gold', 'LOW',
        '["员工里谁最厉害","员工绩效","开单排行","谁开单最多","员工排行","操作员排行","谁最厉害","员工业绩"]'::jsonb,
        '门店开单操作员排行 (gold 层; 注意 staff 为收银/点菜操作员, 非服务员归因)', 90, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET tool_name = 'restaurant_staff_ranking_gold', keywords = EXCLUDED.keywords, business_type = 'RESTAURANT', priority = 90, is_active = true, updated_at = NOW();

-- E2E 第5轮 (餐饮业态) 发现: 两个核心餐饮意图 tool_name 为 NULL → 识别成功但无执行器
-- → 落 ToolRouter 动态兜底 → NEED_CLARIFICATION/无法回答。工具类已注册但 DB 未绑定。
--
-- RESTAURANT_DISH_SALES_RANKING (菜品销售排行) → restaurant_dish_sales_ranking
-- RESTAURANT_TABLE_TURNOVER (翻台率)         → restaurant_table_turnover
--
-- 幂等 (仅绑 tool_name 仍空的行)。
-- ⚠️ 部署后必须调 POST /api/mobile/smartbi-config/intents/reload 清 Redis 意图缓存
--    (allIntents 缓存 Redis-backed, JVM 重启不清; 见 feedback_ai_intent_config_redis_cache_reload)。

UPDATE ai_intent_configs
SET tool_name = 'restaurant_dish_sales_ranking', updated_at = NOW()
WHERE intent_code = 'RESTAURANT_DISH_SALES_RANKING'
  AND (tool_name IS NULL OR tool_name = '');

UPDATE ai_intent_configs
SET tool_name = 'restaurant_table_turnover', updated_at = NOW()
WHERE intent_code = 'RESTAURANT_TABLE_TURNOVER'
  AND (tool_name IS NULL OR tool_name = '');

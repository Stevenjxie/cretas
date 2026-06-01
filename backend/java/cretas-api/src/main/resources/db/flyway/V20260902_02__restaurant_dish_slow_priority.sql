-- 慢销菜品路由修复: "慢销菜品" 误匹配到 RESTAURANT_DISH_LIST (priority=100, 无执行器 → FAILED),
-- 压过了 RESTAURANT_DISH_SLOW (priority=90, 已绑 restaurant_dish_slowseller_gold)。
-- 提升 DISH_SLOW priority 至 110 (> DISH_LIST 的 100), 让带精确关键词"慢销菜品"的
-- DISH_SLOW 胜出。(其余 7 个驾驶舱问题 deployed 实测已正确路由到 gold tool。)
-- flyway 20260902.02 > 20260902.01。幂等。
UPDATE ai_intent_configs
   SET priority = 110, updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_DISH_SLOW';

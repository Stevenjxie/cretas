-- 慢销菜品 仍误匹配 RESTAURANT_DISH_LIST (空孤儿意图: 无 tool_name / 无 keywords /
-- 无 example_queries / 无 patterns, 仅靠 intent_name 语义/分类器命中 "慢销菜品", 且
-- priority 100 压过 DISH_SLOW 的 110 —— 说明它在比 KEYWORD 更高的层(语义/分类器)命中,
-- priority 调整无效)。该意图无执行器, 对任何命中它的查询都 FAILED, 是纯粹的误路由源。
-- 停用它 → 慢销菜品 路由到 RESTAURANT_DISH_SLOW (已绑 restaurant_dish_slowseller_gold)。
-- prod 已手动 UPDATE+restart 验证 8/8 全部正确路由; 本迁移保证 fresh-DB/后续部署一致。
-- flyway 20260902.03。幂等。
UPDATE ai_intent_configs
   SET is_active = false, updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_DISH_LIST';

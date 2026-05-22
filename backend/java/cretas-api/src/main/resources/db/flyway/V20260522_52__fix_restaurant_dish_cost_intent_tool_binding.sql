-- V20260522_52__fix_restaurant_dish_cost_intent_tool_binding.sql
--
-- Sprint 11 — MealClaw Response (Round 4 P1 fix Bug 3).
--
-- Round 3 E2E discovered finance_mgr "查看本月成本分析" routed to
-- RESTAURANT_DISH_COST_ANALYSIS intent but returned:
--   {"status":"FAILED","message":"Skill 执行失败: Skill not found: restaurant-dish-cost-analysis"}
--
-- Root cause:
--   The intent `RESTAURANT_DISH_COST_ANALYSIS` had tool_name=NULL in ai_intent_configs,
--   so executor fell through to `tryExplicitSkillRouteForIntent` which maps
--   intent_code (UPPER_SNAKE) → skill_name (lower-kebab) =
--   "restaurant-dish-cost-analysis". No such Skill registered in SkillRegistryImpl
--   (only restaurant-operations / restaurant-wastage / restaurant-diagnostics exist).
--
-- However, a Tool with name `restaurant_dish_cost_analysis` DOES exist
-- (see RestaurantDishCostAnalysisTool.java line 33-34, @Component since 2026-03-07).
-- It's a fully functional Tool (BOM-based cost analysis with food inventory unit
-- prices and recipe data). The intent config simply forgot to bind tool_name.
--
-- Fix: bind tool_name = 'restaurant_dish_cost_analysis' so executor goes through
-- the Tool direct path (line 306-312 IntentExecutionOrchestrator) instead of
-- the Skill route fallback (which has no matching Skill).
--
-- Per .claude/rules/ai-intent-tool-skill-architecture.md "添加新 Tool 的步骤 2.
-- 绑定意图(数据库)": when Tool exists but intent.tool_name was left NULL, UPDATE
-- the binding to fix.
--
-- Hotfix pattern (per .claude/rules/concurrent-edit-safety.md):
--   - Direct UPDATE on existing intent (was created by Sprint 2/3 RESTAURANT seed)
--   - No-op if RESTAURANT_DISH_COST_ANALYSIS doesn't exist (defensive ROW_COUNT
--     check via psql does NOT exist for UPDATE; instead use idempotency via
--     WHERE clause check)

UPDATE ai_intent_configs
SET tool_name = 'restaurant_dish_cost_analysis',
    updated_at = NOW()
WHERE intent_code = 'RESTAURANT_DISH_COST_ANALYSIS'
  AND (tool_name IS NULL OR tool_name = '');

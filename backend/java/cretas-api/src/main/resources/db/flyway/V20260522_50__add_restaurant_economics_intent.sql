-- V20260522_50__add_restaurant_economics_intent.sql
--
-- Sprint 11 — MealClaw Response (GO 差异化 vs 客如云).
-- 注册 1 Tool-level intent for the Composite RestaurantEconomicsAnalysisTool.
--
-- Composite Tool 编排 3 个 sub-Tool:
--   1. restaurant_store_pnl_one_pager  (P&L 摘要)
--   2. restaurant_shrinkage_analysis    (档口损溢找根因)
--   3. restaurant_cost_rigidity_analysis (成本刚性建议)
--
-- 失败 sub-Tool → dataAvailable=false (per brief §2.5 决策 1).
-- LLM prompt 必须明确标注"X 数据不可用", 不编故事.
--
-- intent_category: SMARTBI (与 14 个现有 RESTAURANT_* intents 一致, per V20260411_01).
-- 注: brief §1 提到 'RESTAURANT_OPERATION' 但 schema 实际用 SMARTBI; 跟现有 14 intents 对齐.
--
-- sensitivity_level: LOW (read-only diagnostic, no data mutation).
--
-- Hotfix pattern from Sprint 10 (per .claude/rules/concurrent-edit-safety.md):
--   - keywords: ::jsonb cast (V_05 || V_36 jsonb 历史教训)
--   - ON CONFLICT (intent_code) DO UPDATE for idempotency
--   - NO `examples` column (Sprint 9 V_36 lesson)

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    keywords, sensitivity_level, is_active, description, priority,
    created_at, updated_at
) VALUES
  (gen_random_uuid(), 'RESTAURANT_ECONOMICS_ANALYSIS', '餐厅经营分析', 'SMARTBI',
   'restaurant_economics_analysis',
   '["帮我看上月损溢","损溢异常","损益分析","成本分析","哪个菜亏钱","上月经营情况","上月损益","餐厅诊断","经营诊断","损益+根因+建议","一句话查损益","损溢分析"]'::jsonb,
   'LOW', true,
   'Sprint 11 MealClaw Response — Composite Tool 编排 P&L + 损溢 + 成本刚性 3 项诊断. 失败 sub-Tool 标注 dataAvailable=false 不编故事.',
   90, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    tool_name = EXCLUDED.tool_name,
    keywords = EXCLUDED.keywords,
    sensitivity_level = EXCLUDED.sensitivity_level,
    is_active = EXCLUDED.is_active,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    updated_at = NOW();

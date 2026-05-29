-- Sprint 12 Phase C — Register SMART_INDICATOR_QUERY intent (Issue #264)
--
-- 根因 (sister audit + bi-tool-output-validation.md): smart-indicator-query SKILL.md
-- (D6a commit a671ed7e9) 代码 ship 了, 但 intent 注册 migration 漏 ship →
-- `POST /ai-intents/execute {intentCode:SMART_INDICATOR_QUERY}` 返
-- "未找到意图配置: SMART_INDICATOR_QUERY" (IntentExecutionOrchestrator:421).
--
-- Wiring (verified in code):
--   - tool_name = NULL → IntentExecutionOrchestrator 走 skill 分支
--     (DynamicToolSelectionService.tryExplicitSkillRouteForIntent)
--   - intentCodeToSkillName('SMART_INDICATOR_QUERY') = 'smart-indicator-query'
--     (toLowerCase().replace('_','-')) → 精确匹配 SKILL.md `name: smart-indicator-query`
--   - skill LLM-routes 到 indicator_query / indicator_comparison / indicator_alert / lineage_query
--
-- priority 90 (= skill priority, 高于 INDICATOR_QUERY 75) — NL 智能路由优先于直接 code 查询.
--
-- 注: NL 12/12 routing 还依赖 main 的 PR #286 orchestrator pipeline-order fix
-- (phrase shortcut 前置 conversation continuation). 本 migration 只补 intent 注册;
-- Phase E rebase main 后 PR #286 + 本注册 合起来达成 12/12.
--
-- Idempotent: ON CONFLICT (intent_code) DO NOTHING.
-- NOTE: ai_intent_configs 有 2 个 unique 约束 — UNIQUE(intent_code) 独立 +
-- UNIQUE(intent_code, factory_id). 必须用 (intent_code) target: 因为 factory_id=NULL
-- (global intent, 同 INDICATOR_QUERY), Postgres unique 中 NULL 视为 distinct, 用
-- (intent_code, factory_id) target 重跑会撞 UNIQUE(intent_code) 而不被 catch → ERROR.

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category,
    tool_name, keywords, priority, is_active, sensitivity_level,
    description, semantic_domain, semantic_action,
    created_at, updated_at
)
VALUES (
    gen_random_uuid()::varchar,
    'SMART_INDICATOR_QUERY',
    '智能指标分析',
    'QUERY',
    NULL,   -- tool_name NULL → skill route (smart-indicator-query)
    -- 单行单 cast: PG `::` 优先级高于 `||`, 多 literal 拼接会让 cast 只作用于最后 fragment
    -- (残缺 JSON) → "invalid input syntax for type json". 必须单 literal 或 (...)::jsonb 整体括.
    '["智能指标", "指标分析", "看指标", "客单价", "平均订单金额", "销售额", "营收", "订单数", "库存", "库存价值", "不合格率", "质检合格率", "出品率", "单位成本", "日均产量", "原料周转", "周转天数", "真空包装", "真空包装合格率", "良品率", "HACCP", "违规", "今天怎么样", "现在状态如何", "几个红灯", "有什么需要关注", "哪个有问题", "对比", "并排看", "几个指标", "溯源", "这批从哪来", "原料用了哪些", "召回查谁"]'::jsonb,
    90,
    TRUE,
    'LOW',
    'AI 工厂 chat 智能指标入口 — 老板问指标/对比/告警/溯源, 自动选 Tool 调度. ' ||
    'tool_name=NULL 走 smart-indicator-query Skill (LLM 路由 4 个 indicator/lineage Tool).',
    'INDICATOR',
    'SMART_QUERY',
    NOW(), NOW()
)
ON CONFLICT (intent_code) DO NOTHING;

-- Verify
DO $$
DECLARE
    smart_intent_count INT;
    smart_tool_name TEXT;
    smart_priority INT;
BEGIN
    SELECT count(*), MAX(tool_name), MAX(priority)
      INTO smart_intent_count, smart_tool_name, smart_priority
      FROM ai_intent_configs WHERE intent_code='SMART_INDICATOR_QUERY' AND is_active=TRUE;

    RAISE NOTICE 'Sprint 12 Phase C: SMART_INDICATOR_QUERY registered: % row(s), tool_name=% (expect NULL→skill), priority=% (expect 90)',
        smart_intent_count, COALESCE(smart_tool_name, 'NULL'), smart_priority;

    IF smart_intent_count < 1 THEN
        RAISE EXCEPTION 'Phase C FAIL: SMART_INDICATOR_QUERY not registered';
    END IF;
    IF smart_tool_name IS NOT NULL THEN
        RAISE EXCEPTION 'Phase C FAIL: SMART_INDICATOR_QUERY tool_name should be NULL (skill route), got %', smart_tool_name;
    END IF;
END $$;

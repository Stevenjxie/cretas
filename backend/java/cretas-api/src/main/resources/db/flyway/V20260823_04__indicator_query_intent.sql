-- V20260823_04__indicator_query_intent.sql
--
-- Sprint 11 D3 — 指标查询 AI 意图 (2026-05-22).
-- 注册 1 个 Tool-level intent (走 Tool 直接执行路径):
--   INDICATOR_QUERY    → indicator_query (READ, LOW sensitivity)
--
-- 由 IndicatorQueryTool.java 实现 (ai/tool/impl/indicator/), 通过 Spring @Component
-- 自动注册到 ToolRegistry, 由 IntentExecutorServiceImpl 路由分支 1 (Tool 直接执行).
--
-- Pattern mirrors V20260822_07 production_demand_query (Sprint 10). 3-strike preflight
-- (per feedback_3_strike_comprehensive_audit_over_reactive_patching HARD):
--   - NO `||` in any string (single literal per CONCAT-rules)
--   - JSONB literal for keywords array
--   - tool_name 非 NULL (Tool 直接执行, 不走 Workdesk Skill)
--   - intent_category=QUERY (per Sprint 9 P0.1 dispatch routing)

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'INDICATOR_QUERY', '指标查询', 'QUERY',
     'indicator_query', 'LOW',
     '["指标查询","看指标","查指标","客单价","翻台率","良品率","食材损耗","食安通过率","计划达成率","菜品毛利","今天客单价多少","良品率怎么样","食安合格吗","损耗率高吗","看一下指标走势","指标趋势","指标现状"]',
     'Sprint 11 D3 — 指标中心查询 Tool. 输入 indicator_code (例 AVG_TICKET_PRICE/FACTORY_YIELD_RATE/FOOD_SAFETY_PASS_RATE), 返回当前值 + 趋势 (默认 30 天) + 阈值命中状态 (GREEN/YELLOW/RED 或 WARNING/ALERT). 数据源 Sprint 11 D2 已 seed F999_MOCK 7 indicator × 30 天 = 210 行 indicator_versions (mock 数据, Sprint 12 切回 prod F006). 触发: 客单价 / 翻台率 / 良品率 / 食安通过率 / 损耗率 / 计划达成率 / 菜品毛利 / 看指标走势.',
     75, true, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

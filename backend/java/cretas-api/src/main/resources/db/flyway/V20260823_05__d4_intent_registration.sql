-- V20260823_05__d4_intent_registration.sql
--
-- Sprint 11 D4 — 注册 2 个 Tool-level intent:
--   LINEAGE_QUERY         → lineage_query (READ, MEDIUM sensitivity 因含批次明细)
--   INDICATOR_COMPARISON  → indicator_comparison (READ, LOW)
--
-- 由 ai/tool/impl/lineage/LineageQueryTool.java +
--    ai/tool/impl/indicator/IndicatorComparisonTool.java 实现.
-- 通过 Spring @Component 自动注册到 ToolRegistry, 由 IntentExecutorServiceImpl
-- 路由分支 1 (Tool 直接执行).
--
-- Pattern mirrors V20260823_04 (D3 IndicatorQueryTool). 3-strike preflight:
--   - NO `||` 在 string literal
--   - JSONB literal keywords array
--   - tool_name 非 NULL (Tool 直接执行)
--   - intent_category=QUERY

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    -- ===== 1. 批次溯源查询 (READ, MEDIUM) =====
    (gen_random_uuid(), 'LINEAGE_QUERY', '批次溯源查询', 'QUERY',
     'lineage_query', 'MEDIUM',
     '["批次溯源","这批用了哪些原料","这批从哪来","原料流向","召回","客户追溯","RES_3101_009","批次追溯","上游下游","哪批原料","流向了哪些客户","物料 lineage","批次来源","溯源链路","溯源 DAG"]',
     'Sprint 11 D4 — 批次溯源 Tool. 输入 batch_type (MATERIAL_BATCH/PRODUCTION_BATCH/FINISHED_BATCH/SHIPMENT_RECORD) + batch_id, 返回该批次的全部祖先 (上游/源批次) + 后代 (下游/客户) + 直接边 metadata (quantityUsed/unit/eventTime/meta). 触发: 这批从哪来 / 这批用了哪批原料 / 召回这批查哪些客户. Sensitivity MEDIUM 因含批次明细 + 客户信息.',
     80, true, NOW(), NOW()),

    -- ===== 2. 指标对比 (READ, LOW) =====
    (gen_random_uuid(), 'INDICATOR_COMPARISON', '指标对比', 'QUERY',
     'indicator_comparison', 'LOW',
     '["指标对比","哪个有问题","几个指标看一下","对比","并列","并比","三个指标摆一起","客单价和翻台率","良品率 vs 食安","哪个最差","worst","看一组","横向对比","几个一起看"]',
     'Sprint 11 D4 — 多指标对比 Tool. 输入 indicator_codes (1-10 个), 返回每个指标当前值 + 告警级别 + worst-state 标识. 适用: 老板问 "客单价和翻台率哪个有问题" / "几个指标摆一起" / "对比 3 个指标". 复用 D3 IndicatorBreachEvaluator 共用工具评估 threshold 命中.',
     76, true, NOW(), NOW())

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

-- Sprint 12 Phase B step 4 — Seed actionHint for 4 REAL_BUSINESS indicators (Issue #265)
--
-- Per .claude/rules/fool-proof-design.md Rule 5: dead-end → next-action. 老板看 KPI 卡片
-- 不应该停在 "数字 dead-end", 应该 1 click 跳到 actionable 页面.
--
-- 数据存储 indicators.config jsonb 字段 nested 对象:
--   {"actionHint": {"label": "<≤8字>", "route": "<frontend router path>"}}
--
-- IndicatorValueResponse.fromWithIndicator() 自动 extract 这个 nested 对象.
-- 老 IndicatorValueResponse.from() (deprecated) 不读 actionHint, 已 mark @Deprecated.
--
-- Route 暂用 best-guess web-admin 路径, 前端 wire 时可调.
-- Idempotent: jsonb_set + EXISTS guard, ON CONFLICT 不适用 (UPDATE 不是 INSERT).

-- ============================================================================
-- Step 1: B2B_AVG_ORDER_VALUE — 跳到销售单列表 (老板可看到哪些大单拉高了 avg)
-- ============================================================================
UPDATE indicators SET config = jsonb_set(
    COALESCE(config, '{}'::jsonb),
    '{actionHint}',
    '{"label": "查看销售单", "route": "/sales/orders"}'::jsonb,
    true)
 WHERE factory_id='F006' AND code='B2B_AVG_ORDER_VALUE';

-- ============================================================================
-- Step 2: B2B_TOTAL_REVENUE_MTD — 跳到本月销售统计
-- ============================================================================
UPDATE indicators SET config = jsonb_set(
    COALESCE(config, '{}'::jsonb),
    '{actionHint}',
    '{"label": "本月销售统计", "route": "/sales/orders?period=MTD"}'::jsonb,
    true)
 WHERE factory_id='F006' AND code='B2B_TOTAL_REVENUE_MTD';

-- ============================================================================
-- Step 3: B2B_ORDER_COUNT_MTD — 跳到本月订单列表
-- ============================================================================
UPDATE indicators SET config = jsonb_set(
    COALESCE(config, '{}'::jsonb),
    '{actionHint}',
    '{"label": "本月订单", "route": "/sales/orders?period=MTD"}'::jsonb,
    true)
 WHERE factory_id='F006' AND code='B2B_ORDER_COUNT_MTD';

-- ============================================================================
-- Step 4: FACTORY_INVENTORY_VALUE — 跳到库存明细 (老板可看到哪些 SKU 占用资金最多)
-- ============================================================================
UPDATE indicators SET config = jsonb_set(
    COALESCE(config, '{}'::jsonb),
    '{actionHint}',
    '{"label": "查看库存明细", "route": "/inventory/material-batches?status=ACTIVE"}'::jsonb,
    true)
 WHERE factory_id='F006' AND code='FACTORY_INVENTORY_VALUE';

-- ============================================================================
-- Step 5: Verify outcome
-- ============================================================================
DO $$
DECLARE
    indicators_with_action_hint INT;
BEGIN
    SELECT count(*) INTO indicators_with_action_hint
      FROM indicators
     WHERE factory_id='F006'
       AND code IN ('B2B_AVG_ORDER_VALUE','B2B_TOTAL_REVENUE_MTD',
                    'B2B_ORDER_COUNT_MTD','FACTORY_INVENTORY_VALUE')
       AND config->'actionHint'->>'label' IS NOT NULL
       AND config->'actionHint'->>'route' IS NOT NULL;

    RAISE NOTICE 'Sprint 12 Phase B step 4: REAL_BUSINESS indicators with actionHint: %/4', indicators_with_action_hint;

    IF indicators_with_action_hint < 4 THEN
        RAISE EXCEPTION 'Sprint 12 Phase B step 4 FAIL: expected 4 actionHints seeded, got %', indicators_with_action_hint;
    END IF;
END $$;

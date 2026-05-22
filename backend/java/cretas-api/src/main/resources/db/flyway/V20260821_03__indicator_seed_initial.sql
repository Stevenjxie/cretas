-- =====================================================================
-- Food Industry Indicator Center — Initial Seed (Phase 1 Sprint 1 Day 2)
-- Spec: docs/superpowers/specs/2026-05-20-food-industry-indicator-center-design.md
--
-- Scope: 7 indicators (5 restaurant + 2 factory) for factory_id='F006' (六腾门).
--        Per "long-term first" principle: seed validation cohort, not all 20.
--        Other 13 spec indicators deferred to Phase 2B (need new Python endpoints).
--
-- Idempotency:
--   - INSERTs use deterministic IDs (ind-f006-<abbr>) so re-runs are safe.
--   - ON CONFLICT (factory_id, code) DO NOTHING on indicators (unique idx exists).
--   - ON CONFLICT (id) DO NOTHING on thresholds/computations (PK based).
--
-- Compute source mapping:
--   - PYTHON_ENDPOINT used only when endpoint actually exists (verified via grep).
--   - Otherwise JPA_QUERY with TODO stub returning 0 (won't crash, signals wiring).
--     Wiring happens in Day 4 IndicatorQueryService.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Indicator definitions — 5 restaurant + 2 factory
-- ---------------------------------------------------------------------
INSERT INTO indicators (id, factory_id, code, name, description, category, unit,
                        is_active, compute_strategy, cache_ttl_seconds, display_order, config)
VALUES
    -- Restaurant indicators (display_order 10-50)
    ('ind-f006-rwr', 'F006', 'RESTAURANT_WASTAGE_RATE',
     '食材损耗率',
     '损耗食材总成本 / 入库食材总成本 × 100%。衡量食材浪费控制水平。',
     'RESTAURANT', '%',
     TRUE, 'CACHED', 300, 10, '{}'::jsonb),

    ('ind-f006-rtt', 'F006', 'RESTAURANT_TABLE_TURNOVER',
     '翻台率',
     '日内每桌平均接待顾客批次数 = 当日总接待批次 / 桌位数。',
     'RESTAURANT', '次/天',
     TRUE, 'CACHED', 300, 20, '{}'::jsonb),

    ('ind-f006-raov', 'F006', 'RESTAURANT_AVG_ORDER_VALUE',
     '平均客单价',
     '总营业额 / 总订单数。反映顾客消费水平。',
     'RESTAURANT', '元',
     TRUE, 'CACHED', 300, 30, '{}'::jsonb),

    ('ind-f006-rdm', 'F006', 'RESTAURANT_DISH_MARGIN',
     '菜品毛利率',
     '(菜品营收 - 菜品原料成本) / 菜品营收 × 100%。核心盈利能力指标。',
     'RESTAURANT', '%',
     TRUE, 'CACHED', 300, 40, '{}'::jsonb),

    ('ind-f006-rfsp', 'F006', 'RESTAURANT_FOOD_SAFETY_PASS',
     '食品安全检查通过率',
     '通过检查项数 / 总检查项数 × 100%。100% 为合规底线。',
     'RESTAURANT', '%',
     TRUE, 'CACHED', 300, 50, '{}'::jsonb),

    -- Factory indicators (display_order 100-110)
    ('ind-f006-fyr', 'F006', 'FACTORY_YIELD_RATE',
     '综合良品率',
     '良品数量 / 总生产数量 × 100%。工厂质量管控核心指标。',
     'FACTORY', '%',
     TRUE, 'CACHED', 300, 100, '{}'::jsonb),

    ('ind-f006-fpar', 'F006', 'FACTORY_PLAN_ACHIEVE_RATE',
     '生产计划达成率',
     '实际产量 / 计划产量 × 100%。衡量生产计划执行情况。',
     'FACTORY', '%',
     TRUE, 'CACHED', 300, 110, '{}'::jsonb)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. Thresholds — one GREEN baseline per indicator
--    (YELLOW/RED can be added later via admin endpoint Day 5)
--
--    Convention:
--      - Higher-is-better metrics (turnover/AOV/margin/yield/plan/safety): GREEN GTE X
--      - Lower-is-better metrics (wastage): GREEN LTE X
-- ---------------------------------------------------------------------
INSERT INTO indicator_thresholds (id, factory_id, indicator_id, alert_level, operator,
                                   threshold_value, threshold_value_upper, is_active)
VALUES
    -- RESTAURANT_WASTAGE_RATE: GREEN ≤3 (lower is better)
    ('thr-f006-rwr-g', 'F006', 'ind-f006-rwr', 'GREEN', 'LTE', 3.0000, NULL, TRUE),

    -- RESTAURANT_TABLE_TURNOVER: GREEN ≥4
    ('thr-f006-rtt-g', 'F006', 'ind-f006-rtt', 'GREEN', 'GTE', 4.0000, NULL, TRUE),

    -- RESTAURANT_AVG_ORDER_VALUE: GREEN ≥80
    ('thr-f006-raov-g', 'F006', 'ind-f006-raov', 'GREEN', 'GTE', 80.0000, NULL, TRUE),

    -- RESTAURANT_DISH_MARGIN: GREEN ≥60
    ('thr-f006-rdm-g', 'F006', 'ind-f006-rdm', 'GREEN', 'GTE', 60.0000, NULL, TRUE),

    -- RESTAURANT_FOOD_SAFETY_PASS: GREEN =100
    ('thr-f006-rfsp-g', 'F006', 'ind-f006-rfsp', 'GREEN', 'EQ', 100.0000, NULL, TRUE),

    -- FACTORY_YIELD_RATE: GREEN ≥95
    ('thr-f006-fyr-g', 'F006', 'ind-f006-fyr', 'GREEN', 'GTE', 95.0000, NULL, TRUE),

    -- FACTORY_PLAN_ACHIEVE_RATE: GREEN ≥90
    ('thr-f006-fpar-g', 'F006', 'ind-f006-fpar', 'GREEN', 'GTE', 90.0000, NULL, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Computations — PYTHON_ENDPOINT where available, JPA_QUERY stubs otherwise
--
--    Verified endpoints (grep'd backend/python/smartbi/ + main.py prefix mapping):
--      - /api/smartbi/restaurant-ops/gross-margin → restaurant_ops_gold.py:182 ✓
--
--    Stubs (TODO Day 4 IndicatorQueryService wiring):
--      - wastage_rate, table_turnover, avg_order_value, food_safety_pass
--      - yield_rate, plan_achieve_rate
-- ---------------------------------------------------------------------
INSERT INTO indicator_computations (id, indicator_id, compute_type, compute_source, params,
                                     is_active, priority)
VALUES
    -- RESTAURANT_WASTAGE_RATE: stub (no endpoint yet; depends on wastage_records aggregation)
    ('cmp-f006-rwr', 'ind-f006-rwr', 'JPA_QUERY',
     'SELECT 0 -- TODO Day 4 IndicatorQueryService: wastage_records SUM(cost) / inbound SUM(cost) * 100',
     '{"unit":"%","scale":4}'::jsonb, TRUE, 1),

    -- RESTAURANT_TABLE_TURNOVER: stub (needs new endpoint or fact_orders aggregation)
    ('cmp-f006-rtt', 'ind-f006-rtt', 'JPA_QUERY',
     'SELECT 0 -- TODO Day 4 IndicatorQueryService: COUNT(orders) / table_count per day',
     '{"unit":"turns/day","scale":2}'::jsonb, TRUE, 1),

    -- RESTAURANT_AVG_ORDER_VALUE: stub (needs new endpoint or fact_orders aggregation)
    ('cmp-f006-raov', 'ind-f006-raov', 'JPA_QUERY',
     'SELECT 0 -- TODO Day 4 IndicatorQueryService: SUM(order_amount) / COUNT(orders)',
     '{"unit":"CNY","scale":2}'::jsonb, TRUE, 1),

    -- RESTAURANT_DISH_MARGIN: PYTHON_ENDPOINT (verified: restaurant_ops_gold.py:182)
    ('cmp-f006-rdm', 'ind-f006-rdm', 'PYTHON_ENDPOINT',
     '/api/smartbi/restaurant-ops/gross-margin',
     '{"unit":"%","scale":4,"value_field":"avg_margin_rate"}'::jsonb, TRUE, 1),

    -- RESTAURANT_FOOD_SAFETY_PASS: stub (needs haccp_checkpoints aggregation;
    -- table created in V20260820_03)
    ('cmp-f006-rfsp', 'ind-f006-rfsp', 'JPA_QUERY',
     'SELECT 0 -- TODO Day 4 IndicatorQueryService: haccp pass count / total * 100',
     '{"unit":"%","scale":4}'::jsonb, TRUE, 1),

    -- FACTORY_YIELD_RATE: stub (needs production_records aggregation)
    ('cmp-f006-fyr', 'ind-f006-fyr', 'JPA_QUERY',
     'SELECT 0 -- TODO Day 4 IndicatorQueryService: good_qty / total_qty * 100',
     '{"unit":"%","scale":4}'::jsonb, TRUE, 1),

    -- FACTORY_PLAN_ACHIEVE_RATE: stub (needs production_plans + actual aggregation)
    ('cmp-f006-fpar', 'ind-f006-fpar', 'JPA_QUERY',
     'SELECT 0 -- TODO Day 4 IndicatorQueryService: actual_qty / planned_qty * 100',
     '{"unit":"%","scale":4}'::jsonb, TRUE, 1)
ON CONFLICT (id) DO NOTHING;

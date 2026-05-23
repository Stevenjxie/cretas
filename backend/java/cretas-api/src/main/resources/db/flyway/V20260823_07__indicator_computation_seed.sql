-- =====================================================================
-- Food Industry Indicator Center — Computation Backfill (Phase 1 Sprint 1 Day 4)
-- Spec: docs/superpowers/specs/2026-05-20-food-industry-indicator-center-design.md
-- Day 4 entry: line 383 of spec
--
-- Status: V20260822_01 is taken (drop_tax_direct); using V20260822_02.
--
-- Scope: Add secondary PYTHON_ENDPOINT placeholder rows (priority=2) for the
--        7 F006 indicators seeded in V20260821_03. These rows wire each
--        indicator to a future Phase 2B Python endpoint as a backup strategy.
--        Primary (priority=1) rows from Day 2 stay intact (JPA_QUERY stubs +
--        the 1 verified PYTHON_ENDPOINT for RESTAURANT_DISH_MARGIN).
--
-- Wiring contract:
--   - IndicatorQueryServiceImpl#fetchFromPython picks priority=1 (Day 2 row).
--   - If priority=1 is JPA_QUERY stub, fetchFromPython returns ZERO with warn log.
--   - Day 5 / Phase 2B can promote priority=2 rows below to priority=1 (or
--     bump existing primary to priority=2) when Python endpoints land — no
--     code change needed.
--
-- Idempotency: deterministic IDs (cmp2-f006-<abbr>) + ON CONFLICT (id) DO NOTHING.
-- =====================================================================

INSERT INTO indicator_computations (id, indicator_id, compute_type, compute_source, params,
                                     is_active, priority)
VALUES
    -- RESTAURANT_WASTAGE_RATE: Phase 2B placeholder endpoint
    ('cmp2-f006-rwr', 'ind-f006-rwr', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/restaurant-wastage-rate',
     '{"unit":"%","scale":4,"value_field":"wastage_rate","todo":"Phase 2B"}'::jsonb,
     TRUE, 2),

    -- RESTAURANT_TABLE_TURNOVER: Phase 2B placeholder
    ('cmp2-f006-rtt', 'ind-f006-rtt', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/restaurant-table-turnover',
     '{"unit":"turns/day","scale":2,"value_field":"turnover","todo":"Phase 2B"}'::jsonb,
     TRUE, 2),

    -- RESTAURANT_AVG_ORDER_VALUE: Phase 2B placeholder
    ('cmp2-f006-raov', 'ind-f006-raov', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/restaurant-avg-order-value',
     '{"unit":"CNY","scale":2,"value_field":"avg_order_value","todo":"Phase 2B"}'::jsonb,
     TRUE, 2),

    -- RESTAURANT_DISH_MARGIN: already has verified PYTHON_ENDPOINT at priority=1;
    -- add a redundant secondary alias for symmetry (same endpoint, alt params).
    ('cmp2-f006-rdm', 'ind-f006-rdm', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/restaurant-dish-margin',
     '{"unit":"%","scale":4,"value_field":"avg_margin_rate","todo":"Phase 2B alias"}'::jsonb,
     TRUE, 2),

    -- RESTAURANT_FOOD_SAFETY_PASS: Phase 2B placeholder
    ('cmp2-f006-rfsp', 'ind-f006-rfsp', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/restaurant-food-safety-pass',
     '{"unit":"%","scale":4,"value_field":"pass_rate","todo":"Phase 2B"}'::jsonb,
     TRUE, 2),

    -- FACTORY_YIELD_RATE: Phase 2B placeholder
    ('cmp2-f006-fyr', 'ind-f006-fyr', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/factory-yield-rate',
     '{"unit":"%","scale":4,"value_field":"yield_rate","todo":"Phase 2B"}'::jsonb,
     TRUE, 2),

    -- FACTORY_PLAN_ACHIEVE_RATE: Phase 2B placeholder
    ('cmp2-f006-fpar', 'ind-f006-fpar', 'PYTHON_ENDPOINT',
     '/api/smartbi/indicator/factory-plan-achieve-rate',
     '{"unit":"%","scale":4,"value_field":"achievement_rate","todo":"Phase 2B"}'::jsonb,
     TRUE, 2)
ON CONFLICT (id) DO NOTHING;

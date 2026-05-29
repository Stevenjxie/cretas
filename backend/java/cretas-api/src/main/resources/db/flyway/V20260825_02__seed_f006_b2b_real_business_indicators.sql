-- Sprint 12 Phase B step 1 — Seed 3 B2B real-business indicators for F006
-- (Issues #263 main + #262 + #265 partial)
--
-- 替换 V_23_11 mirror (Phase A 已删 7 个 F999_MOCK 镜像). 这 3 个真接 sales_orders 业务表,
-- 通过 IndicatorComputationStrategy 路由到 Java {B2BAvgOrderValue/TotalRevenueMtd/OrderCountMtd}Strategy.
--
-- Step 2+ migrations 将在以下 strategy 编码完成后加 indicators:
--   - FACTORY_INVENTORY_VALUE       (material_batches + product_batches)
--   - FACTORY_INVENTORY_TURNOVER    (sales_orders + batches, COGS / AVG inv)
--   - FACTORY_QUALITY_REJECT_RATE   (quality_inspections)
--   - FACTORY_HACCP_VIOLATIONS_MTD  (food_safety_records / haccp_audits)
--
-- 当前只 seed 已 strategy-wired 的 3 个 — 避免 stub 让 dashboard 假装有数据 (违反
-- brief anti-goal "Mock / mirror 当真业务 claim").
--
-- Idempotent: deterministic IDs + ON CONFLICT DO NOTHING.

-- ============================================================================
-- Step 1: 3 个 B2B indicators (compute_strategy=CACHED, ttl=15min)
-- ============================================================================
INSERT INTO indicators (id, factory_id, code, name, description, category, unit,
                        is_active, compute_strategy, cache_ttl_seconds, display_order, config)
VALUES
    ('ind-f006-b2b-aov', 'F006', 'B2B_AVG_ORDER_VALUE',
     '平均订单金额',
     '工厂 B2B 销售订单平均金额 = AVG(total_amount). 排除 DRAFT/CANCELLED/FINANCE_REJECTED. ' ||
     '默认 period 为本月. Sprint 12 Phase B 真接 sales_orders 业务表.',
     'FACTORY', '元',
     TRUE, 'CACHED', 900, 200, '{}'::jsonb),

    ('ind-f006-b2b-trv', 'F006', 'B2B_TOTAL_REVENUE_MTD',
     '本月销售总额',
     '工厂 B2B 销售本月累计 = SUM(total_amount) WHERE order_date >= date_trunc(month, NOW()). ' ||
     '排除 DRAFT/CANCELLED/FINANCE_REJECTED.',
     'FACTORY', '元',
     TRUE, 'CACHED', 900, 201, '{}'::jsonb),

    ('ind-f006-b2b-oct', 'F006', 'B2B_ORDER_COUNT_MTD',
     '本月订单数',
     '工厂 B2B 销售本月订单数 = COUNT(*) WHERE order_date >= date_trunc(month, NOW()). ' ||
     '排除 DRAFT/CANCELLED/FINANCE_REJECTED.',
     'FACTORY', '单',
     TRUE, 'CACHED', 900, 202, '{}'::jsonb)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ============================================================================
-- Step 2: indicator_computations 路由 (compute_type=JPA_AGGREGATE)
--         compute_source 为空 — Java IndicatorComputationStrategyRegistry 按 indicator code 选实现
-- ============================================================================
INSERT INTO indicator_computations (id, indicator_id, compute_type, compute_source, params,
                                     is_active, priority)
VALUES
    ('cmp-f006-b2b-aov', 'ind-f006-b2b-aov', 'JPA_AGGREGATE',
     'java:B2BAvgOrderValueStrategy',
     '{"unit":"CNY","scale":2,"period":"current_month","real_business":true}'::jsonb,
     TRUE, 1),

    ('cmp-f006-b2b-trv', 'ind-f006-b2b-trv', 'JPA_AGGREGATE',
     'java:B2BTotalRevenueMtdStrategy',
     '{"unit":"CNY","scale":2,"period":"MTD","real_business":true}'::jsonb,
     TRUE, 1),

    ('cmp-f006-b2b-oct', 'ind-f006-b2b-oct', 'JPA_AGGREGATE',
     'java:B2BOrderCountMtdStrategy',
     '{"unit":"count","scale":0,"period":"MTD","real_business":true}'::jsonb,
     TRUE, 1)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- Step 3: Verify outcome
-- ============================================================================
DO $$
DECLARE
    b2b_indicator_count INT;
    b2b_computation_count INT;
BEGIN
    SELECT count(*) INTO b2b_indicator_count
      FROM indicators
     WHERE factory_id = 'F006'
       AND code IN ('B2B_AVG_ORDER_VALUE','B2B_TOTAL_REVENUE_MTD','B2B_ORDER_COUNT_MTD');

    SELECT count(*) INTO b2b_computation_count
      FROM indicator_computations c
      JOIN indicators i ON c.indicator_id = i.id
     WHERE i.factory_id = 'F006'
       AND i.code IN ('B2B_AVG_ORDER_VALUE','B2B_TOTAL_REVENUE_MTD','B2B_ORDER_COUNT_MTD')
       AND c.compute_type = 'JPA_AGGREGATE'
       AND c.is_active = TRUE;

    RAISE NOTICE 'Sprint 12 Phase B step 1: F006 B2B indicators seeded: %/3', b2b_indicator_count;
    RAISE NOTICE 'Sprint 12 Phase B step 1: F006 B2B JPA_AGGREGATE computations: %/3', b2b_computation_count;

    IF b2b_indicator_count < 3 THEN
        RAISE EXCEPTION 'Sprint 12 Phase B step 1 FAIL: expected 3 B2B indicators, got %', b2b_indicator_count;
    END IF;
    IF b2b_computation_count < 3 THEN
        RAISE EXCEPTION 'Sprint 12 Phase B step 1 FAIL: expected 3 JPA_AGGREGATE computations, got %', b2b_computation_count;
    END IF;
END $$;

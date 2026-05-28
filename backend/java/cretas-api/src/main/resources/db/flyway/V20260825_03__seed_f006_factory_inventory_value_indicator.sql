-- Sprint 12 Phase B step 2 — Seed FACTORY_INVENTORY_VALUE real-business indicator for F006
-- (Issue #263 main, partial — quality/haccp strategies deferred)
--
-- 配套 Java {@link FactoryInventoryValueStrategy} (SUM(receipt_quantity * unit_price) WHERE
-- status='ACTIVE'). 实测 F006 test env 8 ACTIVE batches → ¥50,095.
--
-- 为什么 step 2 只 ship 1 个 (不是原 spec 4 个):
--   - FACTORY_QUALITY_REJECT_RATE: F006 quality_inspections 0 行 → 返 "0%" 显示
--     "perfect quality" 误导老板 (违反 Rule 21 mock-vs-real honest data 精神).
--     等 F006 真 inspection 数据 OR null-preservation refactor (UI 渲 "—") 再 ship.
--   - FACTORY_HACCP_VIOLATIONS_MTD: F006 haccp_monitoring_records 0 行 → 同样 "0 violations"
--     可能 mean "0 真违规" 也可能 "0 audits" — ambiguous. 同上等数据/null 支持.
--   - FACTORY_INVENTORY_TURNOVER: COGS / AVG(inventory) 算法复杂, 跨月 boundary 难, defer step 3.
--
-- Idempotent: deterministic ID + ON CONFLICT DO NOTHING.

-- ============================================================================
-- Step 1: FACTORY_INVENTORY_VALUE indicator (compute_strategy=CACHED, ttl=15min)
-- ============================================================================
INSERT INTO indicators (id, factory_id, code, name, description, category, unit,
                        is_active, compute_strategy, cache_ttl_seconds, display_order, config)
VALUES
    ('ind-f006-fiv', 'F006', 'FACTORY_INVENTORY_VALUE',
     '库存总价值',
     '工厂在库原材料价值 = SUM(receipt_quantity * unit_price) WHERE status=ACTIVE. ' ||
     'receipt_quantity 是当前剩余 (entity 实现把消耗写回此字段, 非 used_quantity). ' ||
     'Sprint 12 Phase B step 2 真接 material_batches 业务表. Period 参数忽略 (instant snapshot).',
     'FACTORY', '元',
     TRUE, 'CACHED', 900, 203, '{}'::jsonb)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ============================================================================
-- Step 2: indicator_computations 路由 (JPA_AGGREGATE → FactoryInventoryValueStrategy)
-- ============================================================================
INSERT INTO indicator_computations (id, indicator_id, compute_type, compute_source, params,
                                     is_active, priority)
VALUES
    ('cmp-f006-fiv', 'ind-f006-fiv', 'JPA_AGGREGATE',
     'java:FactoryInventoryValueStrategy',
     '{"unit":"CNY","scale":2,"period":"instant","real_business":true,"source_table":"material_batches"}'::jsonb,
     TRUE, 1)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- Step 3: Verify outcome
-- ============================================================================
DO $$
DECLARE
    fiv_indicator_count INT;
    fiv_computation_count INT;
BEGIN
    SELECT count(*) INTO fiv_indicator_count
      FROM indicators WHERE factory_id='F006' AND code='FACTORY_INVENTORY_VALUE';

    SELECT count(*) INTO fiv_computation_count
      FROM indicator_computations c JOIN indicators i ON c.indicator_id=i.id
     WHERE i.factory_id='F006' AND i.code='FACTORY_INVENTORY_VALUE'
       AND c.compute_type='JPA_AGGREGATE' AND c.is_active=TRUE;

    RAISE NOTICE 'Sprint 12 Phase B step 2: FACTORY_INVENTORY_VALUE indicator: %/1', fiv_indicator_count;
    RAISE NOTICE 'Sprint 12 Phase B step 2: FACTORY_INVENTORY_VALUE JPA_AGGREGATE: %/1', fiv_computation_count;

    IF fiv_indicator_count < 1 THEN
        RAISE EXCEPTION 'Sprint 12 Phase B step 2 FAIL: indicator missing';
    END IF;
    IF fiv_computation_count < 1 THEN
        RAISE EXCEPTION 'Sprint 12 Phase B step 2 FAIL: computation missing';
    END IF;
END $$;

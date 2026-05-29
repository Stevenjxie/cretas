-- Sprint 12 Phase B step 5 — Seed 2 quality/HACCP real-business indicators + actionHints
-- (Issue #263 main partial — completes 6/7 Phase B real-business strategies; turnover deferred)
--
-- Per Phase B step 5 null-preserve convention (IndicatorComputationStrategy interface javadoc):
--   - Quality reject rate (ratio): 无 inspection → 返 null → UI "—" (避免 "0% = perfect" 误导)
--   - HACCP violations MTD: 区分 "0 真违规" vs "0 audits", 无 audit → null
--
-- F006 实测 (test cretas_db 2026-05-29):
--   - 0 quality_inspections → FactoryQualityRejectRateStrategy 返 null
--   - 0 haccp_monitoring_records → FactoryHaccpViolationsMtdStrategy 返 null
--   两个 UI 都渲 "—" 而非 "0 / 0%" — honest. 等 F006 真产 inspection/HACCP 数据后自动恢复.
--
-- actionHint 引导老板做 next-action (per fool-proof-design Rule 5):
--   - Quality reject rate → 去看质检记录
--   - HACCP violations → 去看 HACCP 监控

-- ============================================================================
-- Step 1: 2 indicators (CACHED, ttl=15min)
-- ============================================================================
INSERT INTO indicators (id, factory_id, code, name, description, category, unit,
                        is_active, compute_strategy, cache_ttl_seconds, display_order, config)
VALUES
    ('ind-f006-fqrr', 'F006', 'FACTORY_QUALITY_REJECT_RATE',
     '质检不合格率',
     '工厂质检不合格率 = SUM(fail_count) * 100 / SUM(pass_count + fail_count) FROM quality_inspections. ' ||
     '无 inspection 数据返 null → UI "—" (避免 "0%" 误导成 "perfect quality"). ' ||
     'Sprint 12 Phase B step 5 真接 quality_inspections 业务表.',
     'QUALITY', '%',
     TRUE, 'CACHED', 900, 204,
     '{"actionHint": {"label": "查看质检记录", "route": "/quality/inspections"}}'::jsonb),

    ('ind-f006-fhvm', 'F006', 'FACTORY_HACCP_VIOLATIONS_MTD',
     '本月 HACCP 违规',
     '本月 HACCP 监控违规次数 = COUNT(*) WHERE is_deviation=true AND monitoring_time >= MTD. ' ||
     '区分 "0 真违规" vs "0 audits": 当月无 audit 记录返 null → UI "—". ' ||
     'Sprint 12 Phase B step 5 真接 haccp_monitoring_records 业务表.',
     'FOOD_SAFETY', '次',
     TRUE, 'CACHED', 900, 205,
     '{"actionHint": {"label": "HACCP 监控", "route": "/quality/haccp"}}'::jsonb)
ON CONFLICT (factory_id, code) DO NOTHING;

-- ============================================================================
-- Step 2: 2 indicator_computations 路由 (JPA_AGGREGATE)
-- ============================================================================
INSERT INTO indicator_computations (id, indicator_id, compute_type, compute_source, params,
                                     is_active, priority)
VALUES
    ('cmp-f006-fqrr', 'ind-f006-fqrr', 'JPA_AGGREGATE',
     'java:FactoryQualityRejectRateStrategy',
     '{"unit":"%","scale":2,"period":"current_month","real_business":true,"source_table":"quality_inspections","null_when_empty":true}'::jsonb,
     TRUE, 1),

    ('cmp-f006-fhvm', 'ind-f006-fhvm', 'JPA_AGGREGATE',
     'java:FactoryHaccpViolationsMtdStrategy',
     '{"unit":"count","scale":0,"period":"MTD","real_business":true,"source_table":"haccp_monitoring_records","null_when_no_audit":true}'::jsonb,
     TRUE, 1)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- Step 3: Verify outcome
-- ============================================================================
DO $$
DECLARE
    indicator_count INT;
    computation_count INT;
    action_hint_count INT;
BEGIN
    SELECT count(*) INTO indicator_count
      FROM indicators WHERE factory_id='F006'
       AND code IN ('FACTORY_QUALITY_REJECT_RATE','FACTORY_HACCP_VIOLATIONS_MTD');

    SELECT count(*) INTO computation_count
      FROM indicator_computations c JOIN indicators i ON c.indicator_id=i.id
     WHERE i.factory_id='F006'
       AND i.code IN ('FACTORY_QUALITY_REJECT_RATE','FACTORY_HACCP_VIOLATIONS_MTD')
       AND c.compute_type='JPA_AGGREGATE' AND c.is_active=TRUE;

    SELECT count(*) INTO action_hint_count
      FROM indicators WHERE factory_id='F006'
       AND code IN ('FACTORY_QUALITY_REJECT_RATE','FACTORY_HACCP_VIOLATIONS_MTD')
       AND config->'actionHint'->>'label' IS NOT NULL;

    RAISE NOTICE 'Sprint 12 Phase B step 5: indicators: %/2 / computations: %/2 / actionHints: %/2',
        indicator_count, computation_count, action_hint_count;

    IF indicator_count < 2 THEN
        RAISE EXCEPTION 'Phase B step 5 FAIL: expected 2 indicators, got %', indicator_count;
    END IF;
    IF computation_count < 2 THEN
        RAISE EXCEPTION 'Phase B step 5 FAIL: expected 2 computations, got %', computation_count;
    END IF;
    IF action_hint_count < 2 THEN
        RAISE EXCEPTION 'Phase B step 5 FAIL: expected 2 actionHints, got %', action_hint_count;
    END IF;
END $$;

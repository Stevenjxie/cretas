-- Sprint 11 D6+ — unblock F006 INDICATOR_QUERY:
--   1. F006 has 12 indicators but with different codes (RESTAURANT_AVG_ORDER_VALUE etc),
--      not the F999_MOCK convention codes (AVG_TICKET_PRICE etc) that IndicatorQueryTool expects
--   2. Mirror 7 F999_MOCK indicators + thresholds + 30-day versions for F006
--   3. Bump INDICATOR_QUERY intent priority 75 → 90 (above SKU_GROSS_MARGIN 85 / REPORT_QUALITY 85)
--      so keyword match "今天客单价多少" routes here instead of SKU_GROSS_MARGIN
--
-- Idempotent: ON CONFLICT DO NOTHING on factory_id+code unique index
-- Reference: docs/sprint-11/data-source-decision.md + AI 工厂 chat session 2026-05-22

-- ============================================================================
-- Step 1: Bump INDICATOR_QUERY priority to outrank SKU_GROSS_MARGIN / REPORT_QUALITY
-- ============================================================================
UPDATE ai_intent_configs SET priority = 90
WHERE intent_code = 'INDICATOR_QUERY' AND priority < 90;

-- ============================================================================
-- Step 2: Mirror 7 F999_MOCK indicators to F006 (skip if F006 already has the code)
-- ============================================================================
INSERT INTO indicators (
    id, factory_id, code, name, description, category, unit,
    is_active, compute_strategy, cache_ttl_seconds, display_order, config,
    created_at, updated_at
)
SELECT
    gen_random_uuid()::varchar AS id,
    'F006' AS factory_id,
    i.code, i.name, i.description, i.category, i.unit,
    true AS is_active,
    i.compute_strategy, i.cache_ttl_seconds, i.display_order, i.config,
    NOW() AS created_at, NOW() AS updated_at
FROM indicators i
WHERE i.factory_id = 'F999_MOCK'
  AND NOT EXISTS (
      SELECT 1 FROM indicators existing
      WHERE existing.factory_id = 'F006' AND existing.code = i.code
  );

-- ============================================================================
-- Step 3: Mirror thresholds to F006 (look up F006's new indicator IDs by code)
-- ============================================================================
INSERT INTO indicator_thresholds (
    id, factory_id, indicator_id, alert_level, operator,
    threshold_value, threshold_value_upper, is_active,
    created_at, updated_at
)
SELECT
    gen_random_uuid()::varchar AS id,
    'F006' AS factory_id,
    f006_ind.id AS indicator_id,
    t.alert_level, t.operator,
    t.threshold_value, t.threshold_value_upper, t.is_active,
    NOW() AS created_at, NOW() AS updated_at
FROM indicator_thresholds t
JOIN indicators f999 ON t.indicator_id = f999.id AND f999.factory_id = 'F999_MOCK'
JOIN indicators f006_ind ON f006_ind.factory_id = 'F006' AND f006_ind.code = f999.code
WHERE NOT EXISTS (
    SELECT 1 FROM indicator_thresholds existing
    WHERE existing.indicator_id = f006_ind.id
      AND existing.alert_level = t.alert_level
);

-- ============================================================================
-- Step 4: Mirror 30-day indicator_versions to F006 (mirror values from F999_MOCK)
-- ============================================================================
INSERT INTO indicator_versions (
    id, factory_id, indicator_id, period_start, period_end,
    value, computed_at, alert_level, compute_source,
    created_at, updated_at
)
SELECT
    gen_random_uuid()::varchar AS id,
    'F006' AS factory_id,
    f006_ind.id AS indicator_id,
    v.period_start, v.period_end, v.value, v.computed_at,
    v.alert_level,
    'mirrored_from_F999_MOCK' AS compute_source,
    NOW() AS created_at, NOW() AS updated_at
FROM indicator_versions v
JOIN indicators f999 ON v.indicator_id = f999.id AND f999.factory_id = 'F999_MOCK'
JOIN indicators f006_ind ON f006_ind.factory_id = 'F006' AND f006_ind.code = f999.code
WHERE NOT EXISTS (
    SELECT 1 FROM indicator_versions existing
    WHERE existing.indicator_id = f006_ind.id
      AND existing.period_start = v.period_start
      AND existing.period_end = v.period_end
);

-- ============================================================================
-- Step 5: Update indicators.last_value + last_computed_at from latest version
-- ============================================================================
UPDATE indicators i
SET last_value = latest.value,
    last_computed_at = latest.computed_at,
    updated_at = NOW()
FROM (
    SELECT iv.indicator_id, iv.value, iv.computed_at
    FROM indicator_versions iv
    JOIN indicators ii ON iv.indicator_id = ii.id
    WHERE ii.factory_id = 'F006'
      AND iv.compute_source = 'mirrored_from_F999_MOCK'
      AND iv.computed_at = (
          SELECT MAX(iv2.computed_at)
          FROM indicator_versions iv2
          WHERE iv2.indicator_id = iv.indicator_id
      )
) AS latest
WHERE i.id = latest.indicator_id
  AND i.factory_id = 'F006';

-- ============================================================================
-- Step 6: Verify outcome
-- ============================================================================
DO $$
DECLARE
    f006_count INT;
    f006_versions INT;
    intent_priority INT;
BEGIN
    SELECT count(*) INTO f006_count
    FROM indicators
    WHERE factory_id = 'F006'
      AND code IN ('AVG_TICKET_PRICE', 'TABLE_TURNOVER', 'DISH_GROSS_MARGIN',
                   'RAW_WASTAGE_RATE', 'FOOD_SAFETY_PASS_RATE',
                   'FACTORY_YIELD_RATE', 'FACTORY_PLAN_ACHIEVE_RATE');

    SELECT count(*) INTO f006_versions
    FROM indicator_versions iv
    JOIN indicators i ON iv.indicator_id = i.id
    WHERE i.factory_id = 'F006' AND iv.compute_source = 'mirrored_from_F999_MOCK';

    SELECT priority INTO intent_priority
    FROM ai_intent_configs WHERE intent_code = 'INDICATOR_QUERY';

    RAISE NOTICE 'Sprint 11 V_23_11: F006 BI-codes indicators: %/7', f006_count;
    RAISE NOTICE 'Sprint 11 V_23_11: F006 mirrored versions: %', f006_versions;
    RAISE NOTICE 'Sprint 11 V_23_11: INDICATOR_QUERY priority: % (target 90)', intent_priority;

    IF f006_count < 7 THEN
        RAISE EXCEPTION 'Sprint 11 V_23_11 FAIL: expected ≥7 F006 BI-codes, got %', f006_count;
    END IF;
    IF intent_priority < 90 THEN
        RAISE EXCEPTION 'Sprint 11 V_23_11 FAIL: INDICATOR_QUERY priority not bumped, still %', intent_priority;
    END IF;
END $$;

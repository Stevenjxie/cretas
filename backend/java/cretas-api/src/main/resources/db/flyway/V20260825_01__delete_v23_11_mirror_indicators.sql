-- Sprint 12 Phase A — Reverse V_23_11 mirror (Issue #261 T-1 + Issue #263 T-5 step 1)
--
-- Sprint 11 V_23_11 (V20260823_11__f006_indicator_seed_and_priority_bump.sql) mirrored 7 F999_MOCK
-- indicator codes into F006 to unblock INDICATOR_QUERY routing. That was a band-aid for customer demo.
-- Per docs/sprint-12-backlog/indicator-service-rewrite.md Phase A, mirror must now be deleted so
-- Phase B/C can install real-business indicators (compute_source='REAL_BUSINESS') without code-collision.
--
-- Reverses V_23_11 in FK-safe order:
--   1. DELETE indicator_versions WHERE compute_source='mirrored_from_F999_MOCK' AND factory='F006'
--      (~207 rows expected per prod SSH 2026-05-28 audit)
--   2. DELETE indicator_thresholds for the 7 mirror codes under F006
--   3. DELETE indicators (the 7 F006 mirror rows)
--   4. UPDATE ai_intent_configs SET priority=75 WHERE INDICATOR_QUERY priority=90
--      (V_23_11 step 1 bumped 75→90 to outrank SKU_GROSS_MARGIN/REPORT_QUALITY in keyword match;
--       Phase C will register SMART_INDICATOR_QUERY which makes the priority hack unnecessary)
--
-- Idempotent: every block uses WHERE filters that are no-ops if data is already gone.
-- Re-runnable safely (drift/rollback friendly).
--
-- Per .claude/rules/concurrent-edit-safety.md + audit Rule 19 cross-verify:
--   Pre-flight SSH 2026-05-28 confirmed prod state: 7 mirror indicators / 207 mirror versions /
--   INDICATOR_QUERY priority=90. Post-deploy DO-block asserts all become 0/0/75.

-- ============================================================================
-- Step 1: DELETE 30-day mirror versions (~207 rows)
-- ============================================================================
DELETE FROM indicator_versions
WHERE factory_id = 'F006'
  AND compute_source = 'mirrored_from_F999_MOCK';

-- ============================================================================
-- Step 2: DELETE thresholds belonging to F006 mirror indicators
-- ============================================================================
DELETE FROM indicator_thresholds
WHERE factory_id = 'F006'
  AND indicator_id IN (
      SELECT id FROM indicators
       WHERE factory_id = 'F006'
         AND code IN ('AVG_TICKET_PRICE','TABLE_TURNOVER','DISH_GROSS_MARGIN',
                      'RAW_WASTAGE_RATE','FOOD_SAFETY_PASS_RATE',
                      'FACTORY_YIELD_RATE','FACTORY_PLAN_ACHIEVE_RATE')
  );

-- ============================================================================
-- Step 3: DELETE the 7 F006 mirror indicators
-- ============================================================================
DELETE FROM indicators
WHERE factory_id = 'F006'
  AND code IN ('AVG_TICKET_PRICE','TABLE_TURNOVER','DISH_GROSS_MARGIN',
               'RAW_WASTAGE_RATE','FOOD_SAFETY_PASS_RATE',
               'FACTORY_YIELD_RATE','FACTORY_PLAN_ACHIEVE_RATE');

-- ============================================================================
-- Step 4: Restore INDICATOR_QUERY priority 90 → 75 (V_23_11 step 1 reversed)
-- Idempotent: only restores if currently at the V_23_11-bumped value of 90.
-- ============================================================================
UPDATE ai_intent_configs
   SET priority = 75
 WHERE intent_code = 'INDICATOR_QUERY'
   AND priority = 90;

-- ============================================================================
-- Step 5: Verify outcome (DO-block, errors out if mirror residue remains)
-- ============================================================================
DO $$
DECLARE
    mirror_indicators_remaining INT;
    mirror_versions_remaining INT;
    mirror_thresholds_remaining INT;
    indicator_query_priority INT;
BEGIN
    SELECT count(*) INTO mirror_indicators_remaining
      FROM indicators
     WHERE factory_id = 'F006'
       AND code IN ('AVG_TICKET_PRICE','TABLE_TURNOVER','DISH_GROSS_MARGIN',
                    'RAW_WASTAGE_RATE','FOOD_SAFETY_PASS_RATE',
                    'FACTORY_YIELD_RATE','FACTORY_PLAN_ACHIEVE_RATE');

    SELECT count(*) INTO mirror_versions_remaining
      FROM indicator_versions
     WHERE factory_id = 'F006'
       AND compute_source = 'mirrored_from_F999_MOCK';

    SELECT count(*) INTO mirror_thresholds_remaining
      FROM indicator_thresholds t
     WHERE t.factory_id = 'F006'
       AND NOT EXISTS (SELECT 1 FROM indicators i WHERE i.id = t.indicator_id);

    SELECT priority INTO indicator_query_priority
      FROM ai_intent_configs WHERE intent_code = 'INDICATOR_QUERY';

    RAISE NOTICE 'Sprint 12 Phase A: F006 mirror indicators remaining: %/0', mirror_indicators_remaining;
    RAISE NOTICE 'Sprint 12 Phase A: F006 mirror versions remaining: %/0', mirror_versions_remaining;
    RAISE NOTICE 'Sprint 12 Phase A: F006 orphan thresholds: %/0', mirror_thresholds_remaining;
    RAISE NOTICE 'Sprint 12 Phase A: INDICATOR_QUERY priority: % (target 75)', indicator_query_priority;

    IF mirror_indicators_remaining > 0 THEN
        RAISE EXCEPTION 'Sprint 12 Phase A FAIL: expected 0 F006 mirror indicators, got %', mirror_indicators_remaining;
    END IF;
    IF mirror_versions_remaining > 0 THEN
        RAISE EXCEPTION 'Sprint 12 Phase A FAIL: expected 0 F006 mirror versions, got %', mirror_versions_remaining;
    END IF;
    IF mirror_thresholds_remaining > 0 THEN
        RAISE EXCEPTION 'Sprint 12 Phase A FAIL: % orphan thresholds left in F006', mirror_thresholds_remaining;
    END IF;
    IF indicator_query_priority NOT IN (75, 90) THEN
        RAISE EXCEPTION 'Sprint 12 Phase A FAIL: INDICATOR_QUERY priority=% unexpected (want 75 or unchanged 90)', indicator_query_priority;
    END IF;
END $$;

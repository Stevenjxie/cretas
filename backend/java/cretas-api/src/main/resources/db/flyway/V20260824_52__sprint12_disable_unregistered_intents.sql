-- Sprint 12 residue: disable intents whose Tool is not registered.
-- Audit found "本月主要缺陷类型分布" routes to RESTAURANT_OPS_WASTAGE_TOP which
-- references unregistered Tool [restaurant_ops_wastage_top], causing FAILED status.
-- Disable so classifier reroutes via fallback to NEED_CLARIFICATION or alternative intent.

UPDATE ai_intent_configs SET is_active = false
WHERE intent_code = 'RESTAURANT_OPS_WASTAGE_TOP'
  AND tool_name = 'restaurant_ops_wastage_top';

-- Verification
DO $$
DECLARE
  inactive_count INT;
BEGIN
  SELECT COUNT(*) INTO inactive_count
  FROM ai_intent_configs
  WHERE intent_code = 'RESTAURANT_OPS_WASTAGE_TOP' AND is_active = false;
  IF inactive_count = 0 THEN
    RAISE NOTICE 'No-op: RESTAURANT_OPS_WASTAGE_TOP not present in ai_intent_configs';
  ELSE
    RAISE NOTICE 'Sprint 12 V_52: disabled % unregistered RESTAURANT_OPS_WASTAGE_TOP intent', inactive_count;
  END IF;
END $$;

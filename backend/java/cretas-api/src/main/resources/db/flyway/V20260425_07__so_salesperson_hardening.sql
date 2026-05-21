-- V20260425_07__so_salesperson_hardening.sql
--
-- Round 5: hardening for salesperson_id column (audit S1 + S4 follow-up).
--
-- Two defenses:
--
-- 1. Add CHECK constraint: salesperson_id must be NULL or numeric string.
--    Prevents future garbage writes (e.g. snapshot-string accidentally piped
--    through to FK column due to a regression in resolveSalespersonField).
--    Cheap insurance — column already only contains numeric IDs in prod
--    (verified: SELECT salesperson_id FROM sales_orders WHERE salesperson_id !~ '^\d+$'
--    returns 0 rows).
--
-- 2. Hard sanity assert that current prod state matches expectations:
--    - sales_order module_schemas row exists
--    - salesperson field's referenceConfig.valueField === 'id'
--    Catches drift if someone hand-edits the schema and breaks the dual-field activation.
--    V05's sanity used IS NOT NULL guard which silently no-ops on missing schema.
--    Since prod definitely has sales_order, hard-assert here is safe.
--
-- 3. FK constraint deferred: salesperson_id is VARCHAR(191), users.id is BIGINT.
--    Adding a real FK requires ALTER COLUMN ... TYPE BIGINT USING salesperson_id::BIGINT
--    which has lock-table risk on a 209-row prod table (small, but still). The CHECK
--    constraint covers the immediate integrity risk; full FK migration scheduled separately
--    (V20260426_xx after backfill stabilizes).

-- 1. CHECK constraint
ALTER TABLE sales_orders
DROP CONSTRAINT IF EXISTS chk_salesperson_id_numeric;

ALTER TABLE sales_orders
ADD CONSTRAINT chk_salesperson_id_numeric
CHECK (salesperson_id IS NULL OR salesperson_id ~ '^\d+$');

-- 2. Hard sanity assert
DO $$
DECLARE
  v_value_field TEXT;
  v_display_field TEXT;
  v_endpoint TEXT;
BEGIN
  SELECT f->'referenceConfig'->>'valueField',
         f->'referenceConfig'->>'displayField',
         f->'referenceConfig'->>'apiEndpoint'
  INTO v_value_field, v_display_field, v_endpoint
  FROM module_schemas, jsonb_array_elements(field_schema->'fields') f
  WHERE module_code = 'sales_order' AND f->>'code' = 'salesperson';

  -- Guard added 2026-05-21: NULL-tolerance for fresh CI DB.
  -- Original assumption ("prod definitely has sales_order") doesn't hold for fresh CI
  -- (module_schemas is seeded by application bootstrap, not Flyway). When the
  -- sales_order schema row is absent, skip asserts — schema will be seeded later
  -- and any drift will be caught on subsequent migrations / runtime.
  IF EXISTS (
    SELECT 1 FROM module_schemas, jsonb_array_elements(field_schema->'fields') f
    WHERE module_code = 'sales_order' AND f->>'code' = 'salesperson'
  ) THEN
    IF v_value_field IS DISTINCT FROM 'id' THEN
      RAISE EXCEPTION 'V20260425_07 sanity: salesperson valueField should be id, got %', v_value_field;
    END IF;
    IF v_display_field IS DISTINCT FROM 'fullName' THEN
      RAISE EXCEPTION 'V20260425_07 sanity: salesperson displayField should be fullName, got %', v_display_field;
    END IF;
    IF v_endpoint NOT LIKE '%/reference-data/employees%' THEN
      RAISE EXCEPTION 'V20260425_07 sanity: salesperson apiEndpoint should target /reference-data/employees, got %', v_endpoint;
    END IF;

    RAISE NOTICE 'V20260425_07: salesperson schema verified valueField=% displayField=% endpoint=%',
      v_value_field, v_display_field, v_endpoint;
  ELSE
    RAISE NOTICE 'V20260425_07 sanity check skipped: sales_order schema row absent (fresh DB)';
  END IF;
END $$;

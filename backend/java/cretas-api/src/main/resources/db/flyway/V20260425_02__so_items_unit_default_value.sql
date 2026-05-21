-- V20260425_02__so_items_unit_default_value.sql
--
-- Found by depth-first-e2e Rule 8: V20260425_01 added unit options 但 没 defaultValue,
-- 导致用户加明细行时 unit 字段为空, 提交时后端 @NotBlank 拒绝 (HTTP 400 "单位不能为空").
-- Deep L4 test caught this on first roundtrip attempt.
--
-- Fix: 给 sales_order.items.unit 字段加 defaultValue="kg" (食品行业最常用),
-- 用户加新行时自动填 kg, 减少 1 步操作 + 防止漏选 → 400.
-- Backwards-compat: 加新行有默认值, 老数据已存的 unit 不受影响.

UPDATE module_schemas
SET field_schema = jsonb_set(
    field_schema,
    '{fields}',
    (
        SELECT jsonb_agg(
            CASE
                WHEN f->>'code' = 'items' THEN
                    jsonb_set(
                        f,
                        '{itemSchema,fields}',
                        (
                            SELECT jsonb_agg(
                                CASE
                                    WHEN itm->>'code' = 'unit' THEN
                                        jsonb_set(itm, '{defaultValue}', '"kg"')
                                    ELSE itm
                                END
                            )
                            FROM jsonb_array_elements(f->'itemSchema'->'fields') itm
                        )
                    )
                ELSE f
            END
        )
        FROM jsonb_array_elements(field_schema->'fields') f
    )
)
WHERE module_code = 'sales_order'
  AND field_schema IS NOT NULL
  AND jsonb_typeof(field_schema->'fields') = 'array';

DO $$
DECLARE
  v_default TEXT;
BEGIN
  SELECT itm->>'defaultValue' INTO v_default
  FROM module_schemas,
       jsonb_array_elements(field_schema->'fields') f,
       jsonb_array_elements(f->'itemSchema'->'fields') itm
  WHERE module_code='sales_order' AND f->>'code'='items' AND itm->>'code'='unit';

  RAISE NOTICE 'V20260425_02: items.unit defaultValue = %', v_default;
  -- Guard added 2026-05-21: NULL-tolerance for fresh CI DB (sales_order row absent)
  IF v_default IS NOT NULL AND v_default IS DISTINCT FROM 'kg' THEN
    RAISE EXCEPTION 'V20260425_02 sanity failure: items.unit defaultValue should be "kg", got %', v_default;
  END IF;
END $$;

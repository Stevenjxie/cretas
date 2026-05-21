-- C-6 Canvas Reactive Default: sales_order items 加 reactive defaults
-- 2026-05-09: Phase B Task 5 follow-up (PR #198 deferred portion)
-- Spec: docs/superpowers/specs/2026-05-09-canvas-c6-sales-order-dynamic-migration.md
--
-- 改动:
-- 1. items.itemSchema.fields[productTypeId].referenceConfig 加 projectFields 把
--    /reference-data/products/{id} 响应的 boxConversionCoefficient + specification
--    写到 row 的 _ 前缀 shadow 字段.
-- 2. items.itemSchema.fields 追加 boxQuantity (decimal, computed=null-guard 表达式, readonly).
--
-- 重要: sales_order 是 V20260409_02 格式 ({"fields":[...]}+itemSchema.fields[code]),
-- 不像 purchase_order V20260410_08 格式 ([{...}]+itemFields[fieldCode]).
-- 因此 SQL 跟 V20260509_01 (purchase_order) 不能 reuse, 需走 sales_order 自己的 path.
--
-- field_schema -> fields[8] 是 items field (0-indexed, items 在 sales_order 第 9 个顶层字段)
-- field_schema -> fields[8] -> itemSchema -> fields 是 line items 的字段定义 (8 个: productTypeId,
--   specification, quantity, unit, unitPrice, taxRate, lineAmount, remark).
--
-- 后端 dep: ReferenceDataController.findProducts + getProduct 在同 PR 里加
-- m.put("boxConversionCoefficient", p.getBoxConversionCoefficient()), 否则 frontend
-- ReferenceSelector projectFields 拿 undefined → boxQuantity 永远 = null.
--
-- abaca null-guard: P1-3 抄码品识别. specification 含 '抄码' (LEGACY exact match
-- ' === 抄码', 但 schema computed 用 includes 容忍 '抄码 200g' 等变体, 跟 PR #173
-- LEGACY 的 isAbacaItem 略有差异 — 待客户确认是否 strict-match 后调整).
--
-- LEGACY 兼容: PR #173 LEGACY P1-2 实现 (sales/orders/list.vue:325-334) 不受影响,
-- 此 migration 只改 module_schemas, 影响 DYNAMIC 渲染路径 (DynamicModulePage).
-- F001 sales_order=DYNAMIC 是主要受益者 (PR #173 I-3 documented limitation 解锁).

-- Pre-flight check: 验证 sales_order schema 包含 items field, 且 items 在 fields[8] 位置 (0-indexed)
-- Class 4 Flyway gap fix (2026-05-21): sales_order seed V20260409_02 lives in
-- legacy db/migration/ dir (NOT Flyway-scanned). Fresh DBs (CI) lack the row.
-- Mirror V20260425_01 lenient pattern: skip assert when row absent, UPDATE below
-- naturally no-ops via WHERE module_code='sales_order'. Canvas seed service /
-- Hibernate handles bootstrap on prod first-boot.
DO $$
DECLARE
  has_row BOOLEAN;
  has_items BOOLEAN;
  items_idx INTEGER;
BEGIN
  -- Skip entirely if sales_order row absent (fresh DB, seed-not-applied env)
  SELECT EXISTS (
    SELECT 1 FROM module_schemas WHERE module_code = 'sales_order'
  ) INTO has_row;

  IF NOT has_row THEN
    RAISE NOTICE 'V20260510_01 skipped pre-flight assert: sales_order row absent (fresh DB / unprovisioned)';
    RETURN;
  END IF;

  -- 找 items field 位置
  SELECT idx - 1 INTO items_idx
  FROM module_schemas, jsonb_array_elements(field_schema->'fields') WITH ORDINALITY AS arr(elem, idx)
  WHERE module_code = 'sales_order'
    AND elem->>'code' = 'items'
  LIMIT 1;

  has_items := items_idx IS NOT NULL;

  IF NOT has_items THEN
    RAISE EXCEPTION 'sales_order schema 缺 items field (row exists but items missing, schema corruption)';
  END IF;

  -- 验证 items 是 line_items type
  IF NOT EXISTS (
    SELECT 1
    FROM module_schemas, jsonb_array_elements(field_schema->'fields') AS f
    WHERE module_code = 'sales_order'
      AND f->>'code' = 'items'
      AND f->>'type' = 'line_items'
  ) THEN
    RAISE EXCEPTION 'sales_order items field 不是 line_items type, 不应该 migrate';
  END IF;

  -- 验证 itemSchema.fields 含 productTypeId
  IF NOT EXISTS (
    SELECT 1
    FROM module_schemas,
         jsonb_array_elements(field_schema->'fields') AS f,
         jsonb_array_elements(f->'itemSchema'->'fields') AS itf
    WHERE module_code = 'sales_order'
      AND f->>'code' = 'items'
      AND itf->>'code' = 'productTypeId'
  ) THEN
    RAISE EXCEPTION 'sales_order items.itemSchema.fields 缺 productTypeId';
  END IF;
END $$;

-- Rebuild fields array, modify items.itemSchema.fields with C-6 enhancements
UPDATE module_schemas
SET field_schema = jsonb_set(
  field_schema,
  '{fields}',
  (
    SELECT jsonb_agg(
      CASE
        WHEN field->>'code' = 'items'
        THEN jsonb_set(
          field,
          '{itemSchema,fields}',
          (
            -- Step 1: 重建 itemSchema.fields 数组, 注入 productTypeId.projectFields
            SELECT jsonb_agg(
              CASE
                -- productTypeId: 注入 projectFields 进 referenceConfig
                -- COALESCE 保留 V20260425_* 已设的 entity/displayField/valueField/apiEndpoint,
                -- 仅追加 projectFields key.
                WHEN itemfield->>'code' = 'productTypeId'
                THEN itemfield || jsonb_build_object(
                  'referenceConfig',
                  COALESCE(itemfield->'referenceConfig', '{}'::jsonb) || jsonb_build_object(
                    'projectFields', jsonb_build_object(
                      'boxConversionCoefficient', '_boxConversionCoefficient',
                      'specification', '_specification'
                    )
                  )
                )
                ELSE itemfield
              END
            )
            FROM jsonb_array_elements(field->'itemSchema'->'fields') AS itemfield
          )
          -- Step 2: 追加 boxQuantity itemField (含 abaca null-guard), 已存在则跳过
          || CASE
               WHEN EXISTS (
                 SELECT 1
                 FROM jsonb_array_elements(field->'itemSchema'->'fields') AS x
                 WHERE x->>'code' = 'boxQuantity'
               )
               THEN '[]'::jsonb  -- already exists, no-op
               ELSE jsonb_build_array(jsonb_build_object(
                 'code', 'boxQuantity',
                 'label', '箱数',
                 'type', 'decimal',
                 'required', false,
                 'precision', 2,
                 'computed',
                   'quantity > 0 && _boxConversionCoefficient != null && _boxConversionCoefficient > 0 ' ||
                   '&& (_specification == null || !_specification.includes(''抄码'')) ' ||
                   '? quantity / _boxConversionCoefficient : null',
                 'readonly', true
               ))
             END
        )
        ELSE field
      END
    )
    FROM jsonb_array_elements(field_schema->'fields') AS field
  )
)
WHERE module_code = 'sales_order';

-- Post-verify: 应能查到 productTypeId.referenceConfig.projectFields.boxConversionCoefficient
-- = '_boxConversionCoefficient' 且 itemSchema.fields 末尾有 boxQuantity computed
-- Class 4 fix (2026-05-21): post-verify also skipped on fresh-DB / unprovisioned envs.
-- UPDATE above no-ops via WHERE module_code='sales_order' when row absent.
DO $$
DECLARE
  has_row BOOLEAN;
  has_project_fields BOOLEAN;
  has_box_quantity BOOLEAN;
  has_specification_shadow BOOLEAN;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM module_schemas WHERE module_code = 'sales_order'
  ) INTO has_row;

  IF NOT has_row THEN
    RAISE NOTICE 'V20260510_01 skipped post-verify: sales_order row absent (no-op UPDATE)';
    RETURN;
  END IF;

  SELECT EXISTS (
    SELECT 1
    FROM module_schemas,
         jsonb_array_elements(field_schema->'fields') AS f,
         jsonb_array_elements(f->'itemSchema'->'fields') AS itf
    WHERE module_code = 'sales_order'
      AND f->>'code' = 'items'
      AND itf->>'code' = 'productTypeId'
      AND itf->'referenceConfig'->'projectFields' ? 'boxConversionCoefficient'
  ) INTO has_project_fields;

  SELECT EXISTS (
    SELECT 1
    FROM module_schemas,
         jsonb_array_elements(field_schema->'fields') AS f,
         jsonb_array_elements(f->'itemSchema'->'fields') AS itf
    WHERE module_code = 'sales_order'
      AND f->>'code' = 'items'
      AND itf->>'code' = 'productTypeId'
      AND itf->'referenceConfig'->'projectFields' ? 'specification'
  ) INTO has_specification_shadow;

  SELECT EXISTS (
    SELECT 1
    FROM module_schemas,
         jsonb_array_elements(field_schema->'fields') AS f,
         jsonb_array_elements(f->'itemSchema'->'fields') AS itf
    WHERE module_code = 'sales_order'
      AND f->>'code' = 'items'
      AND itf->>'code' = 'boxQuantity'
      AND itf->>'computed' IS NOT NULL
  ) INTO has_box_quantity;

  IF NOT has_project_fields THEN
    RAISE EXCEPTION 'C-6 sales_order migration verify failed: productTypeId.referenceConfig.projectFields.boxConversionCoefficient not found';
  END IF;
  IF NOT has_specification_shadow THEN
    RAISE EXCEPTION 'C-6 sales_order migration verify failed: productTypeId.referenceConfig.projectFields.specification not found';
  END IF;
  IF NOT has_box_quantity THEN
    RAISE EXCEPTION 'C-6 sales_order migration verify failed: items.itemSchema.fields[boxQuantity].computed not found';
  END IF;
END $$;

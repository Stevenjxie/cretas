-- C-6 Canvas Reactive Default Framework: purchase_order items 加 reactive defaults
-- 2026-05-09: Phase B Task 5 (per docs/superpowers/specs/2026-05-09-canvas-c6-reactive-default-framework.md)
--
-- 改动:
-- 1. items.itemFields[materialTypeId] 加完整 referenceConfig (entity/displayField/valueField/apiEndpoint/projectFields)
--    projectFields 把 raw-material-types/{id} 响应的 level1PerLevel2 + level2Unit 写到 row 的 _ 前缀 shadow 字段.
-- 2. items.itemFields 追加 boxQuantity (decimal, computed='quantity > 0 && _level1PerLevel2 != null && _level1PerLevel2 > 0 ? quantity / _level1PerLevel2 : null', readOnly).
--
-- jsonb_set 的 path 不支持数组 deep index 写法 — 用 jsonb_array_elements + jsonb_agg 重建 itemFields 数组.
-- field_schema 是 top-level array (V20260410_08:9 seed 格式 `[{...},{...}]`), items 字段在 array 第 7 索引位.
--
-- 后端 dep: RawMaterialTypeServiceImpl.getMaterialTypeById 在本 migration 同 PR 里加了
-- packagingRepository.findByMaterialTypeId(id) enrich, 输出 DTO 含 level1PerLevel2 + level2Unit.
-- 没装包装层级的 material 返 null 字段, 前端 boxQuantity computed 表达式做 null-guard.
--
-- LEGACY 兼容: 已有 schema (PR #173 LEGACY P1-2/P1-3 餐饮 audio batch) 在 sales/orders/list.vue
-- 等硬编码组件里, 不走 module_schemas. 此 migration 只影响 DYNAMIC mode (renderingMode='DYNAMIC')
-- 渲染 purchase_order 的 SchemaFormRenderer/LineItemsEditor 路径. F001 sales_order=DYNAMIC 类似工厂将受益.

-- Pre-flight check: 验证 purchase_order schema 包含 items 字段 (best-effort)
-- Class 4 Flyway gap fix (2026-05-21): purchase_order seed V20260410_08 lives in
-- legacy db/migration/ dir (NOT Flyway-scanned). Fresh DBs (CI) lack the row.
-- Mirror V20260425_01 lenient pattern: skip assert when row absent, UPDATE below
-- naturally no-ops via WHERE clause. Canvas seed service / Hibernate handles
-- bootstrap on prod first-boot.
DO $$
DECLARE
  has_row BOOLEAN;
  has_items BOOLEAN;
BEGIN
  -- Skip entirely if purchase_order row absent (fresh DB, seed-not-applied env)
  SELECT EXISTS (
    SELECT 1 FROM module_schemas WHERE module_code = 'purchase_order'
  ) INTO has_row;

  IF NOT has_row THEN
    RAISE NOTICE 'V20260509_01 skipped pre-flight assert: purchase_order row absent (fresh DB / unprovisioned)';
    RETURN;
  END IF;

  SELECT EXISTS (
    SELECT 1
    FROM module_schemas, jsonb_array_elements(field_schema) AS f
    WHERE module_code = 'purchase_order'
      AND f->>'fieldCode' = 'items'
  ) INTO has_items;
  IF NOT has_items THEN
    RAISE EXCEPTION 'purchase_order field_schema 缺 items 字段 (row exists but items missing, schema corruption)';
  END IF;
END $$;

-- Rebuild items.itemFields with C-6 enhancements
UPDATE module_schemas
SET field_schema = (
  SELECT jsonb_agg(
    CASE
      -- items 字段: 重建 itemFields 数组
      WHEN field->>'fieldCode' = 'items'
      THEN field || jsonb_build_object(
        'itemFields',
        (
          SELECT jsonb_agg(
            CASE
              -- materialTypeId: 注入完整 referenceConfig (含 projectFields)
              -- 已存在 referenceConfig 时也覆盖, 因为 V20260425_* 系列只设了
              -- referenceModule + apiEndpoint, 没有 displayField/valueField/projectFields.
              WHEN itemfield->>'fieldCode' = 'materialTypeId'
              THEN itemfield || jsonb_build_object(
                'referenceConfig', jsonb_build_object(
                  'entity', 'rawMaterialType',
                  'displayField', 'name',
                  'valueField', 'id',
                  'apiEndpoint', '/api/mobile/{factoryId}/raw-material-types',
                  'projectFields', jsonb_build_object(
                    'level1PerLevel2', '_level1PerLevel2',
                    'level2Unit', '_level2Unit'
                  )
                )
              )
              ELSE itemfield
            END
          )
          FROM jsonb_array_elements(field->'itemFields') AS itemfield
        )
        -- 追加 boxQuantity itemField (向数组尾部加, 已存在则跳过)
        || CASE
             WHEN EXISTS (
               SELECT 1
               FROM jsonb_array_elements(field->'itemFields') AS x
               WHERE x->>'fieldCode' = 'boxQuantity'
             )
             THEN '[]'::jsonb  -- already exists, no-op append
             ELSE jsonb_build_array(jsonb_build_object(
               'fieldCode', 'boxQuantity',
               'label', '箱数',
               'type', 'decimal',
               'required', false,
               'precision', 2,
               'computed', 'quantity > 0 && _level1PerLevel2 != null && _level1PerLevel2 > 0 ? quantity / _level1PerLevel2 : null',
               'readOnly', true
             ))
           END
      )
      ELSE field
    END
  )
  FROM jsonb_array_elements(field_schema) AS field
)
WHERE module_code = 'purchase_order';

-- Verify: 应能查到 materialTypeId.referenceConfig.projectFields._level1PerLevel2 = 'level1PerLevel2'
-- Class 4 fix (2026-05-21): post-verify also skipped on fresh-DB / unprovisioned envs.
-- UPDATE above no-ops via WHERE module_code='purchase_order' when row absent.
DO $$
DECLARE
  has_row BOOLEAN;
  has_project_fields BOOLEAN;
  has_box_quantity BOOLEAN;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM module_schemas WHERE module_code = 'purchase_order'
  ) INTO has_row;

  IF NOT has_row THEN
    RAISE NOTICE 'V20260509_01 skipped post-verify: purchase_order row absent (no-op UPDATE)';
    RETURN;
  END IF;

  SELECT EXISTS (
    SELECT 1
    FROM module_schemas, jsonb_array_elements(field_schema) AS f,
         jsonb_array_elements(f->'itemFields') AS itf
    WHERE module_code = 'purchase_order'
      AND f->>'fieldCode' = 'items'
      AND itf->>'fieldCode' = 'materialTypeId'
      AND itf->'referenceConfig'->'projectFields' ? 'level1PerLevel2'
  ) INTO has_project_fields;

  SELECT EXISTS (
    SELECT 1
    FROM module_schemas, jsonb_array_elements(field_schema) AS f,
         jsonb_array_elements(f->'itemFields') AS itf
    WHERE module_code = 'purchase_order'
      AND f->>'fieldCode' = 'items'
      AND itf->>'fieldCode' = 'boxQuantity'
      AND itf->>'computed' IS NOT NULL
  ) INTO has_box_quantity;

  IF NOT has_project_fields THEN
    RAISE EXCEPTION 'C-6 migration verify failed: materialTypeId.referenceConfig.projectFields.level1PerLevel2 not found';
  END IF;
  IF NOT has_box_quantity THEN
    RAISE EXCEPTION 'C-6 migration verify failed: items.itemFields[boxQuantity].computed not found';
  END IF;
END $$;

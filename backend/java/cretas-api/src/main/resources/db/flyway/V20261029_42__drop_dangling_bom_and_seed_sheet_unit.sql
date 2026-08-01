-- 两件事: ① 删掉指向不存在 SKU 的悬空 BOM  ② 把「张」补进单位名录。
--
-- =============================================================
-- ① 悬空 BOM (Steve 2026-08-01 拍板「删除吧」)
-- =============================================================
-- `BOM-20260708-001 干式熟成鸡（半只）`(LIUSHANMEN) 的 product_type_id
-- b4f16b12-003d-4b64-a464-d94d69e17292 在 product_types 里**根本没有这一行**
-- —— 不是软删, 是不存在。所以它:
--   - 没有 SKU 可对齐单位(V20261029_41 的 JOIN 天然排除了它)
--   - 用户一旦打开/激活它, 走 `loadProductForUpdate` 会直接抛错
--
-- 用**软删**(deleted_at) 而不是 DELETE: 与本仓 BOM 的既有删除语义一致,
-- 且留痕可查、可人工还原。
--
-- ⚠️ 护栏: 只删「product_type_id 在 product_types 里找不到」的行。
-- 用 NOT EXISTS 而不是写死 id —— 写死 id 只能治这一条, 判据式能顺带兜住同类。
-- 但仍限定 factory_id + recipe_code, 避免误伤将来出现的其它悬空数据
-- (那些应当单独评估, 不该被这条迁移静默清掉)。

UPDATE bom_recipes b
SET deleted_at = now(), updated_at = now()
WHERE b.deleted_at IS NULL
  AND b.factory_id = 'LIUSHANMEN'
  AND b.recipe_code = 'BOM-20260708-001'
  AND NOT EXISTS (
      SELECT 1 FROM product_types p WHERE p.id = b.product_type_id
  );

-- =============================================================
-- ② 「张」补进名录 (Steve 2026-08-01 拍板「做吧」)
-- =============================================================
-- 走查发现「张」**两个来源都不认**:
--   - DB 名录 unit_of_measurements: 没有
--   - Java 权威别名表 systemAliases(): 也没有(文件里出现的 3 次「张」全在注释里)
-- 于是 `normalize()` 两层都返回 unrecognized →
-- `RawMaterialTypeServiceImpl#normalizeInventoryUnit` 抛
-- 「该单位不能用于入库计量」。
--
-- 影响: LIUSHANMEN 3 个 + F006 3 个标签包材 SKU, **在 SKU 编辑页保存会 400**。
-- 目前 0 批次 0 BOM 引用, 属**潜伏缺陷** —— 没人碰就不发作, 一碰就报错。
--
-- 规范码取 `sheet`(与 roll/slice 同族的英文计数码), 中文名「张」。
-- 同批还要在 Java 权威别名表补 alias("sheet","sheet","张"), 见同 PR 的代码改动 ——
-- **只补名录不补别名表, 兜底那层仍然不认**(名录优先但并非唯一入口)。
--
-- 幂等: NOT EXISTS 按 (factory_id, unit_code) 判重, 与表上唯一约束同键。

INSERT INTO unit_of_measurements (
    id, factory_id, unit_code, unit_name, unit_symbol,
    category, conversion_family, base_unit, is_base_unit, conversion_factor, decimal_places,
    is_system, is_active, sort_order, usage_scopes_json, created_at, updated_at
)
SELECT
    gen_random_uuid()::varchar, '*', 'sheet', '张', '张',
    'COUNT', 'COUNT', 'pcs', false, 1.000000, 0,
    true, true, 17,
    '["INVENTORY_QUANTITY", "PURCHASE_QUANTITY", "BOM_QUANTITY", "SPECIFICATION"]'::jsonb,
    now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM unit_of_measurements existing
    WHERE existing.factory_id = '*' AND lower(existing.unit_code) = 'sheet'
);

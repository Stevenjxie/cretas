-- ============================================================================
-- 存量单位码订正 —— 把库里剩下的英文计数/包装码归一成契约的 canonical 码
--
-- 日期: 2026-08-18
-- 库:   cretas_prod_db (schema = public)
-- 性质: 数据订正, **不是** Flyway 迁移 —— 故意放在 scripts/migrations/ 下,
--       由主线决定何时执行, 不随部署自动跑。
--
-- ---------------------------------------------------------------------------
-- 为什么需要它
-- ---------------------------------------------------------------------------
-- 判据: 「单位: 录入 / 显示 / 换算后三处一致; 不许英文单位, 用户看到必须是中文」。
--
-- 2026-08-18 prod 实测: GET /api/mobile/F006/production-plans/{id} 返回
--   "plannedUnit": "盒", "workflowOutputUnit": "盒", "sourceDisplayUnit": "box"
-- 字段名里带 display, 值却是英文码。
--
-- 代码侧已经修好两处出口(见同一 PR):
--   1. ProductionPlanMapper 的单位字段一律经 UnitDisplayNames.display()
--   2. UnitDisplayNames 补齐了英文【单词】别名(ton/carton/pieces…)
-- 但那只保证「用户看到的是中文」。**库里存的仍是英文码**, 而判据要求的是
-- 录入/显示/换算三处一致 —— 所以存量也要归一, 否则:
--   - 任何绕过该出口的新代码又会把码漏出去
--   - 同一个单位在库里有两种写法, 分组/去重/比较各自为政
--
-- ---------------------------------------------------------------------------
-- canonical 的方向 (⚠️ 不是「全部改成中文」)
-- ---------------------------------------------------------------------------
-- 按 UnitContractServiceImpl#systemAliases() 的取舍:
--   * 计数/包装: canonical 就是**中文字**, 英文是别名  → box → 盒, pcs → 件
--   * 质量/体积/长度: canonical 是**国际符号**, 中文和英文单词都是别名
--                     → ton → t  (⛔ 不是「吨」: GB 3100 里吨的法定符号就是 t)
--   * jin 是 canonical 本身(拼音码), **保持原样** —— 由 UnitDisplayNames 在
--     展示时翻成「斤」。⛔ 不要在这里把 jin 改成 斤。
--
-- ---------------------------------------------------------------------------
-- 幂等性
-- ---------------------------------------------------------------------------
-- 每条 UPDATE 的 WHERE 都按「当前值等于那个英文码」筛。跑完之后不再有行匹配,
-- 重复执行是 no-op。可以安全地重跑。
--
-- ---------------------------------------------------------------------------
-- 预期影响行数 (2026-08-18 14:58 实测, 全库逐列扫 230 个 unit/uom 文本列)
-- ---------------------------------------------------------------------------
--   bom_recipes.output_unit                    box   6
--   bom_recipe_items.unit                      pcs   2
--   bom_recipe_items.price_unit                pcs   2
--   bom_recipe_items.natural_unit              pcs   2
--   production_plans.source_display_unit       box   2  + case 1
--   sales_order_items.unit                     box   2  + case 1
--   material_packaging_specs.package_unit      case  2  + ton  1
--   material_packaging_hierarchy.level2_unit   ton   1
--                                                   ---------
--                                              合计  22 行
--
--   另有 1 行 product_unit_conversions.from_unit_code = 'pcs' **故意不在本脚本内**,
--   见文末「DELIBERATELY_NOT_UPDATED」。
--
-- 🔴🔴 ---------------------------------------------------------------------
-- 这个数会自己长大 —— 写入侧还在产出英文码, 跑之前必须重新量
-- ---------------------------------------------------------------------------
-- 同一次会话里, 上面两个「box」从 1 涨到 2: `production_plans` 和
-- `sales_order_items` 各有一行是 **2026-08-18 当天 14:50 / 14:54 新建的**。
-- ⇒ 这**不是存量清理**, 是一条还在漏的管子下面接了个桶。
--
-- 链路 (读代码追出来, 未改):
--   sales_order_items.unit = 'box'                        ← 真正的源头, 待定位
--     └→ SalesOrderPlanQuantityNormalizer:73 把它原样当 sourceUnit
--          └→ ProductionPlanServiceImpl:1930 存进 production_plans.source_display_unit
--   (中间两跳都是**忠实快照**, 行为正确 —— 病在最上游写 sales_order_items 的那条路)
--
-- ⇒ 本脚本只清桶。上游没修之前, 跑完还会再脏。
-- ⇒ 用户看到的那一面**已经不依赖本脚本**: ProductionPlanMapper 的出口无条件
--    走 UnitDisplayNames.display(), 所以库里即使是 box, API 返回的也是「盒」。
--    本脚本要解决的是判据的另一半 ——「录入/显示/换算三处一致」里的**存储**那一处。
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- 用法
-- ---------------------------------------------------------------------------
--   1) 先只跑 §1 的 DRY RUN, 核对行数与上表一致(数据在动, 可能已经不同)
--   2) 行数对得上再跑 §2
--   3) 跑完 §3 复核, 期望「剩余违例 = 0 行」
--
--   psql -h localhost -U cretas_user -d cretas_prod_db -f <本文件>
-- ============================================================================


-- ============================================================================
-- §1 DRY RUN —— 先看要改多少行, 什么都不改
-- ============================================================================
\echo '=== §1 DRY RUN: 待订正的行数 ==='

SELECT 'bom_recipes.output_unit'                  AS target, quantity_unit AS value, count(*) AS rows
FROM (SELECT output_unit AS quantity_unit FROM public.bom_recipes) s
WHERE lower(btrim(quantity_unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'bom_recipe_items.unit', unit, count(*) FROM public.bom_recipe_items
WHERE lower(btrim(unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'bom_recipe_items.price_unit', price_unit, count(*) FROM public.bom_recipe_items
WHERE lower(btrim(price_unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'bom_recipe_items.natural_unit', natural_unit, count(*) FROM public.bom_recipe_items
WHERE lower(btrim(natural_unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'production_plans.source_display_unit', source_display_unit, count(*) FROM public.production_plans
WHERE lower(btrim(source_display_unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'sales_order_items.unit', unit, count(*) FROM public.sales_order_items
WHERE lower(btrim(unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'material_packaging_specs.package_unit', package_unit, count(*) FROM public.material_packaging_specs
WHERE lower(btrim(package_unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
UNION ALL
SELECT 'material_packaging_hierarchy.level2_unit', level2_unit, count(*) FROM public.material_packaging_hierarchy
WHERE lower(btrim(level2_unit)) IN ('box','case','pcs','ton') GROUP BY 1,2
ORDER BY 1, 2;


-- ============================================================================
-- §2 订正 —— 一个事务, 要么全成要么全不动
-- ============================================================================
\echo '=== §2 UPDATE (事务内) ==='

BEGIN;

-- 计数/包装: canonical = 中文字
UPDATE public.bom_recipes SET output_unit = '盒'
 WHERE lower(btrim(output_unit)) = 'box';

UPDATE public.bom_recipe_items SET unit = '件'
 WHERE lower(btrim(unit)) = 'pcs';
UPDATE public.bom_recipe_items SET price_unit = '件'
 WHERE lower(btrim(price_unit)) = 'pcs';
UPDATE public.bom_recipe_items SET natural_unit = '件'
 WHERE lower(btrim(natural_unit)) = 'pcs';

UPDATE public.production_plans SET source_display_unit = '盒'
 WHERE lower(btrim(source_display_unit)) = 'box';
UPDATE public.production_plans SET source_display_unit = '箱'
 WHERE lower(btrim(source_display_unit)) = 'case';

UPDATE public.sales_order_items SET unit = '盒'
 WHERE lower(btrim(unit)) = 'box';
UPDATE public.sales_order_items SET unit = '箱'
 WHERE lower(btrim(unit)) = 'case';

UPDATE public.material_packaging_specs SET package_unit = '箱'
 WHERE lower(btrim(package_unit)) = 'case';

-- 质量: canonical = 国际符号 t, ⛔ 不是「吨」
UPDATE public.material_packaging_specs SET package_unit = 't'
 WHERE lower(btrim(package_unit)) = 'ton';
UPDATE public.material_packaging_hierarchy SET level2_unit = 't'
 WHERE lower(btrim(level2_unit)) = 'ton';

COMMIT;


-- ============================================================================
-- §3 复核 —— 期望 0 行
-- ============================================================================
\echo '=== §3 复核: 剩余违例 (期望 0 行) ==='

SELECT * FROM (
  SELECT 'bom_recipes.output_unit' AS target, output_unit AS value FROM public.bom_recipes
   WHERE lower(btrim(output_unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'bom_recipe_items.unit', unit FROM public.bom_recipe_items
   WHERE lower(btrim(unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'bom_recipe_items.price_unit', price_unit FROM public.bom_recipe_items
   WHERE lower(btrim(price_unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'bom_recipe_items.natural_unit', natural_unit FROM public.bom_recipe_items
   WHERE lower(btrim(natural_unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'production_plans.source_display_unit', source_display_unit FROM public.production_plans
   WHERE lower(btrim(source_display_unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'sales_order_items.unit', unit FROM public.sales_order_items
   WHERE lower(btrim(unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'material_packaging_specs.package_unit', package_unit FROM public.material_packaging_specs
   WHERE lower(btrim(package_unit)) IN ('box','case','pcs','ton')
  UNION ALL
  SELECT 'material_packaging_hierarchy.level2_unit', level2_unit FROM public.material_packaging_hierarchy
   WHERE lower(btrim(level2_unit)) IN ('box','case','pcs','ton')
) leftovers ORDER BY 1,2;

-- ⚠️ 阳性对照: §3 那条查询「0 行」有两种可能 —— 真的改干净了, 或者这几列压根没数据
--    (那样「0 违例」只是空转)。所以再数一次**非空行数**, 它必须 > 0。
--
-- 🔴 这里刻意数「非空」而不是「中文」: 第一版写的是 `WHERE col ~ '[一-鿿]'`,
--    实测 production_plans.source_display_unit 的中文行数是 **0** ——
--    那一列**全部 3 行都是 box/case**, 一行中文都没有。用「中文行数 > 0」当对照,
--    它在订正**之前**必然为 0 ⇒ 对照自己先红, 而它红并不说明订正有问题。
--    「这一列有没有数据」才是这里真正要证明的事。
\echo '=== §3b 阳性对照: 这几列的非空行数 (必须 > 0, 否则上面的「0 违例」是空转) ==='
SELECT 'bom_recipes.output_unit' AS target, count(*) AS non_null_rows FROM public.bom_recipes
 WHERE output_unit IS NOT NULL
UNION ALL
SELECT 'production_plans.source_display_unit', count(*) FROM public.production_plans
 WHERE source_display_unit IS NOT NULL
UNION ALL
SELECT 'sales_order_items.unit', count(*) FROM public.sales_order_items
 WHERE unit IS NOT NULL
ORDER BY 1;


-- ============================================================================
-- DELIBERATELY_NOT_UPDATED —— 登记「故意不改」, 留痕不是豁免
-- ============================================================================
-- 1) product_unit_conversions.from_unit_code = 'pcs' (1 行)
--    这是**换算图的键**, 不是展示值。同表 to_unit_code 存的是 g(95) / kg(3)。
--    改键要和引用它的那一侧同时改, 否则换算链断开 —— 而换算断开是静默的
--    (查不到换算 → 回落 → 数字悄悄错), 比显示一个英文码严重得多。
--    ⇒ 单独评估, 不混在本次「显示层」订正里。
--
-- 2) material_packaging_specs.package_unit = 'jin' (1 行)
--    jin 是 canonical 码本身(拼音码, 不是别名), 由 UnitDisplayNames 在展示时
--    翻成「斤」。改成「斤」反而让存储偏离契约。
--
-- 3) work_processes.unit = 'unitless' (20 行)
--    WorkProcessServiceImpl:80 硬编码写入的**哨兵值**, 语义是「这道工序没有单位」,
--    与 UnitDisplayNamesTest 里已确立的 'mixed' 哨兵同族。它不是一个单位,
--    翻译它没有意义。⚠️ 但它**确实会渲染到用户面前**
--    (web-admin views/system/product-processes/index.vue:1575,
--     RN ProductWorkProcessConfigScreen.tsx:476) —— 正确修法是渲染层显示成
--    「无」或留空, 而不是给哨兵值编一个中文名。单列一条待办。
--
-- 4) quality_check_items.unit = 'score' (1 行) / production_transit_ledgers.quantity_unit = '?' (1 行)
--    各 1 行, 分别是「评分」和一个明显的脏值。数据源头不明, 不在本次机械订正范围内。
--
-- 5) 备份/归档 schema (bak_*, f006_clear_71, legacy_retired, tenant_purge_68)
--    与 public.backup_* / bak_* / repair_backup_* 表: 快照数据, 按定义就该保持
--    当时的原样, ⛔ 不订正。
-- ============================================================================

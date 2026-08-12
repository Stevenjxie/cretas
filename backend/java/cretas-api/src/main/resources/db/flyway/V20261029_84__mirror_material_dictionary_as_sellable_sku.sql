-- 物料字典 → 可售 SKU 的一次性回填
--
-- 2026-08-12 Steve 拍板(六膳门张权:「老问题 销售订单 选择不了原料」
-- 「有啥不能卖的 给钱 我都能卖」;「半成品卖 过分了」):
--   「以后录入原料字典就是录入原料 SKU」——不要「发布」这个动作。
--
-- 增量由 MaterialSkuMirrorService 在物料保存后自动镜像; 本迁移只补历史存量。
-- 范围: 全部租户(Steve 确认「全部」)。实测 7 个工厂共 552 条活跃物料。
--
-- 为什么必须建镜像而不是直接卖物料:
--   sales_order_items.product_type_id 是 NOT NULL 且整表没有指向物料的列
--   (实测该表只有 sales_order_id 一条外键)。要在销售订单里选到物料,
--   它就得在商品目录里有一份。
--
-- 为什么不会污染生产侧:
--   镜像一律 product_category='RAW_MATERIAL', 而生产侧的
--   findVisibleByFactoryIdAndIsActiveTrue 明确排除该类别 ——
--   生产计划/批次/工时/毛利红线那些下拉一条都不会多, 只出现在销售侧的 /sellable。

-- ⚠️ 只回填【启用】的物料。停用的物料不该出现在销售下拉里,
--    而且把它们也建出来会凭空多几百条死数据。
INSERT INTO product_types (
    id, factory_id, code, name, category, product_category,
    unit, unit_price, is_active, created_by, created_at, updated_at
)
SELECT
    'PTM_' || r.id,
    r.factory_id,
    -- 与 MaterialSkuMirrorService.mirrorCode 同一口径: M- 前缀 + 截断到 50
    LEFT('M-' || r.code, 50),
    r.name,
    r.category,
    'RAW_MATERIAL',
    r.unit,
    r.unit_price,
    TRUE,
    r.created_by,
    NOW(),
    NOW()
FROM raw_material_types r
WHERE r.is_active
  AND r.created_by IS NOT NULL          -- created_by 是 NOT NULL, 取不到就不建
  -- 幂等: 编号已存在就跳过(重跑安全, 也让手工建过同编号商品的工厂不被覆盖)
  AND NOT EXISTS (
      SELECT 1 FROM product_types p
      WHERE p.factory_id = r.factory_id
        AND p.code = LEFT('M-' || r.code, 50)
  )
  -- ⚠️ 产品名在厂内唯一是【应用层】规则(库上没有唯一索引, 见 pg_indexes:
  --    只有 id 与 (factory_id, code) 两个唯一索引)。纯 SQL 插入不会被拦, 但会造出
  --    一批「存在即违规」的行 —— 用户下次编辑它们就会撞 409。所以这里主动跳过重名的,
  --    宁可少建几条也不留下自相矛盾的数据。实测全库 13 条会撞(六膳门两个厂都是 0)。
  AND NOT EXISTS (
      SELECT 1 FROM product_types p2
      WHERE p2.factory_id = r.factory_id
        AND LOWER(TRIM(p2.name)) = LOWER(TRIM(r.name))
  )
  -- 同厂物料自己重名时只取一条(实测全库 3 条), 否则这一批内部就会互相违规
  AND r.id = (
      SELECT r2.id FROM raw_material_types r2
      WHERE r2.factory_id = r.factory_id
        AND r2.is_active
        AND LOWER(TRIM(r2.name)) = LOWER(TRIM(r.name))
      ORDER BY r2.code
      LIMIT 1
  );

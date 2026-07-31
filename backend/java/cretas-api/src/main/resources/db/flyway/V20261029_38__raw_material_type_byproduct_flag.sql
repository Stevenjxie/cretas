-- 副产改用「标记」而不是 category 取值 (2026-07-31)
--
-- 背景: Task 1 把副产做成了 category = '副产'。2026-07-31 走前端验收时撞出两个问题:
--
-- 1) 建不出来。原料字典「新建原料类型」的类别下拉取的是**物料分段字典的 L1 类族**
--    (web-admin material-types/list.vue 的 materialFamilyOptions), prod 上 F006 的 L1 只有
--    001 原料 / 002 包材 / 003 辅料 —— 没有「副产」, 所以副产 SKU 一个都建不出来,
--    BOM 第四类的物料下拉永远是空的, 整条链(报工落生产仓 → 盘点抵扣)都到不了。
--
-- 2) 🔴 更要命: category='副产' 会堵死这个设计自己的目标。副产放原料字典的理由是
--    「好让它以后能当原料被别的 workflow 投入」, 但 BOM「原料」页签的放行集合是 ['原料'],
--    而副产的 category 是「副产」→ 它在原料页签里永远选不到, 当不成投入。
--
-- 根因: 「副产」描述的是**来历**(生产产出、无采购来源), 与物料**是什么材质**正交。
-- L1/L2/L3 是材质分类树(牛肉部位 → 眼肉); 把来历塞进材质树是范畴错误, 还会逼着在
-- 副产底下把 L2/L3 再复制一遍。
--
-- 改法: 材质分类照旧走 category + L1/L2/L3(肥油就是油脂/原料), 副产性单独一个布尔标记。
-- 于是同一个 SKU 既能被认成副产(排除采购、进 BOM 第四类、落生产仓), 又天然还是原料
-- (能被别的 workflow 当投入选到)。
--
-- 🔴 存量影响 = 0: 线上 raw_material_types 764 行中 category='副产' 的有 **0 行**
--    (2026-07-31 prod 实测, 阳性对照: 同查询 count(*)=764), 所以没有数据需要迁移。
--    这也是改这条契约最便宜的时刻 —— 再往后有副产 SKU 了就得做数据迁移。

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS is_byproduct BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN raw_material_types.is_byproduct IS
    '是否副产: 生产产出物而非采购物。true = 排除出采购/补货建议, 可在 BOM 第四类被声明; '
    '与 category 正交 —— 副产仍保留其材质分类(如原料), 因此能被别的 workflow 当投入投料';

-- 补货建议按工厂扫描物料, 副产没有采购来源, 走部分索引避免全表扫
CREATE INDEX IF NOT EXISTS idx_raw_material_types_byproduct
    ON raw_material_types (factory_id)
    WHERE is_byproduct = TRUE;

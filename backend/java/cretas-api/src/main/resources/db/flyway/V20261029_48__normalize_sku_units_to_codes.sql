-- =============================================================================
-- V20261029_48: SKU 单位统一存英文码
--
-- 背景 / 为什么是码不是中文
--   prod 实测两个表**方向相反**: 原料 660 英文 / 106 中文, 成品 139 英文 / 629 中文。
--   客户撞到的「报工单位**袋**, BOM 单位 **bag**」409 就是这么来的 —— 不是数据脏,
--   是两边存的形态不一样。
--
--   选码不是偏好, 是**别名映射多对一、反过来查不出来**:
--   权威表 UnitContractServiceImpl.systemAliases() 里
--   alias("pcs","件","个","只","pc","piece","pieces") —— 七种写法归一到一个 pcs。
--   存码只有一个规范形, 展示层(UnitDisplayNames)挑一个中文词显示;
--   存中文就没有规范形 —— 库里同时有「个/只/件」, 比较/去重/换算都得先猜哪个是主。
--   prod 数据直接印证: 原料里 个67 + 只3 + 件2 是三个词对同一个码;
--   kg598 + KG28 + 公斤2 是同一个单位的三种写法。
--
--   写入侧 RawMaterialTypeServiceImpl#normalizeInventoryUnit 早就返回 normalized.code(),
--   是 V20261029_32 把数据改成中文, 两边打架 —— 所以「修好又漂回去」。这条终结它。
--
-- 映射来源
--   下面的 zh->code 逐字抄自 UnitContractServiceImpl:885-913 的 systemAliases()。
--   ⚠️ 改权威表必须同步改这里(反之亦然)。
--
-- 影响面 (2026-08-02 prod cretas_prod_db 实测)
--   原料 raw_material_types : 106 行中文 → 全部可映射
--   成品 product_types      : 629 行中文 → 628 可映射, **1 行映射不出**
--   另有大小写不规范: 原料 KG 28 行、L 20 行 → 小写化
--
-- ⛔ 映射不出的**不猜**
--   F006「干式熟成鸡—前处理」的 unit 是「半只」—— 那是**规格不是单位**(零库存批次)。
--   迁移**不动它**, 只在末尾 RAISE NOTICE 报出来交人工定。
--   宁可留一行不规范, 也不把「半只」瞎折成 pcs 让下游按 1 只算。
--
-- 回滚
--   db/manual-rollback/V20261029_48__normalize_sku_units_to_codes_rollback.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS backup_sku_units_20260802 (
    table_name  VARCHAR(32),
    row_id      VARCHAR(64),
    old_unit    VARCHAR(64),
    new_unit    VARCHAR(64),
    backed_up_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (table_name, row_id)
);

-- 权威别名表 (zh -> code), 逐字对应 systemAliases()
CREATE TEMP TABLE _unit_alias(zh TEXT PRIMARY KEY, code TEXT);
INSERT INTO _unit_alias VALUES
 ('毫克','mg'),('克','g'),('公斤','kg'),('千克','kg'),('斤','jin'),('吨','t'),
 ('毫升','ml'),('升','l'),
 ('毫米','mm'),('公厘','mm'),('厘米','cm'),('公分','cm'),('米','m'),('公尺','m'),
 ('千米','km'),('公里','km'),
 ('件','pcs'),('个','pcs'),('只','pcs'),
 ('份','portion'),('盒','box'),('箱','case'),('袋','bag'),('包','pack'),
 ('瓶','bottle'),('罐','can'),('框','crate'),('筐','crate'),('桶','pail'),
 ('卷','roll'),('片','slice'),('张','sheet'),('托盘','tray'),('板','plate'),('项','item');

-- ---------- 原料 ----------
INSERT INTO backup_sku_units_20260802 (table_name, row_id, old_unit, new_unit)
SELECT 'raw_material_types', t.id, t.unit, a.code
FROM raw_material_types t JOIN _unit_alias a ON a.zh = t.unit
WHERE t.deleted_at IS NULL AND t.unit IS NOT NULL AND t.unit <> ''
ON CONFLICT (table_name, row_id) DO NOTHING;

UPDATE raw_material_types t SET unit = b.new_unit, updated_at = NOW()
FROM backup_sku_units_20260802 b
WHERE b.table_name = 'raw_material_types' AND b.row_id = t.id AND t.unit = b.old_unit;

-- 大小写: KG -> kg, L -> l (只动纯 ASCII 且与小写形不同的)
INSERT INTO backup_sku_units_20260802 (table_name, row_id, old_unit, new_unit)
SELECT 'raw_material_types', t.id, t.unit, lower(t.unit)
FROM raw_material_types t
WHERE t.deleted_at IS NULL AND t.unit IS NOT NULL AND t.unit <> ''
  AND t.unit !~ '[一-龥]' AND t.unit <> lower(t.unit)
ON CONFLICT (table_name, row_id) DO NOTHING;

UPDATE raw_material_types t SET unit = lower(t.unit), updated_at = NOW()
WHERE t.deleted_at IS NULL AND t.unit IS NOT NULL AND t.unit <> ''
  AND t.unit !~ '[一-龥]' AND t.unit <> lower(t.unit);

-- ---------- 成品 ----------
INSERT INTO backup_sku_units_20260802 (table_name, row_id, old_unit, new_unit)
SELECT 'product_types', t.id, t.unit, a.code
FROM product_types t JOIN _unit_alias a ON a.zh = t.unit
WHERE t.deleted_at IS NULL AND t.unit IS NOT NULL AND t.unit <> ''
ON CONFLICT (table_name, row_id) DO NOTHING;

UPDATE product_types t SET unit = b.new_unit, updated_at = NOW()
FROM backup_sku_units_20260802 b
WHERE b.table_name = 'product_types' AND b.row_id = t.id AND t.unit = b.old_unit;

INSERT INTO backup_sku_units_20260802 (table_name, row_id, old_unit, new_unit)
SELECT 'product_types', t.id, t.unit, lower(t.unit)
FROM product_types t
WHERE t.deleted_at IS NULL AND t.unit IS NOT NULL AND t.unit <> ''
  AND t.unit !~ '[一-龥]' AND t.unit <> lower(t.unit)
ON CONFLICT (table_name, row_id) DO NOTHING;

UPDATE product_types t SET unit = lower(t.unit), updated_at = NOW()
WHERE t.deleted_at IS NULL AND t.unit IS NOT NULL AND t.unit <> ''
  AND t.unit !~ '[一-龥]' AND t.unit <> lower(t.unit);

-- ---------- 收尾: 报出仍不规范的行 (不阻断) ----------
DO $$
DECLARE r RECORD; n INTEGER := 0;
BEGIN
    FOR r IN
        SELECT 'raw_material_types' AS tbl, id, name, unit FROM raw_material_types
        WHERE deleted_at IS NULL AND unit IS NOT NULL AND unit <> '' AND unit ~ '[一-龥]'
        UNION ALL
        SELECT 'product_types', id, name, unit FROM product_types
        WHERE deleted_at IS NULL AND unit IS NOT NULL AND unit <> '' AND unit ~ '[一-龥]'
    LOOP
        n := n + 1;
        RAISE NOTICE 'V48 未归一(映射不出, 需人工定): %.% name=% unit=%', r.tbl, r.id, r.name, r.unit;
    END LOOP;
    RAISE NOTICE 'V48 完成: 台账 % 行, 仍不规范 % 行 (干跑时: 台账 780, 剩 1 = F006「半只」)',
        (SELECT count(*) FROM backup_sku_units_20260802), n;
END $$;

DROP TABLE _unit_alias;

COMMENT ON TABLE backup_sku_units_20260802 IS
    'V20261029_48 台账: SKU 单位归一到英文码前的原值, 供回滚使用。';

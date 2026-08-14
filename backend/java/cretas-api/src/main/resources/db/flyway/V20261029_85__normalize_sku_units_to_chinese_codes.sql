-- =============================================================================
-- V20261029_85: 非科学换算单位改存中文码 (反向归一 V20261029_48)
--
-- 为什么反过来
--   V20261029_48 选英文码的理由写得很清楚: 别名是**多对一**的 ——
--   alias("pcs","件","个","只",...) 七种写法归一到一个 pcs, 存中文就没有规范形,
--   库里同时有「个/只/件」时比较/去重/换算都得先猜哪个是主。那个理由在当时成立。
--
--   本次改动把这个前提**拆掉了**: UnitContractServiceImpl 里非科学换算单位的码
--   改成中文字本身, 件/个/只 各自成为独立单位, 框/筐 同理。多对一消失,
--   每个中文单位天然就是自己的规范形 —— 存中文不再有歧义。
--
--   ⚠️ 科学换算单位 (kg/g/mg/t/ml/l/mm/cm/m/km) **不在本次范围**:
--   它们有恒定换算, 符号是国际通用写法, 且 kg←公斤/千克 这类同义写法是
--   同一个单位的两个名字, 不是两个单位。本迁移一个字都不动它们。
--
-- 为什么现在做
--   2026-08-14 实测: 库里中英两套写法同时存在(活跃原料包装规格 case 29 / 箱 7,
--   后者全是默认包装), 后端字面比较必然误判 ——「默认包装的原料压根调不动」(409)。
--   同一个洞 2026-07-31 就咬过客户一次(见 web-admin/src/utils/unitPricing.ts 注释)。
--   代码侧已改为中文码; 数据不同步就会出现「码是中文而库里是英文」的新错位,
--   所以代码与数据必须同一次上线。
--
-- 映射来源
--   逐条抄自 UnitContractServiceImpl.systemAliases() 改后的版本。
--   ⚠️ 改权威表必须同步改这里(反之亦然) —— SkuUnitStorageIsCodeContractTest 钉住这条。
--
-- ⛔ 映射不出的不猜
--   与 V20261029_48 同一条原则: 只动能在别名表里查到的, 其余原样留下并 RAISE NOTICE
--   点名, 交人工定。宁可留一行不规范, 也不瞎折。
--
-- 回滚
--   备份表 backup_sku_units_zh_20260814 记录了每行的原值, 反向 UPDATE 即可。
-- =============================================================================

CREATE TABLE IF NOT EXISTS backup_sku_units_zh_20260814 (
    table_name  VARCHAR(64),
    row_id      VARCHAR(64),
    column_name VARCHAR(32),
    old_value   VARCHAR(64),
    backed_up_at TIMESTAMP DEFAULT NOW()
);

DO $$
DECLARE
    unmapped_count INT := 0;
    r RECORD;
BEGIN
    -- 英文码 → 中文码。仅非科学换算单位。
    CREATE TEMP TABLE _zh_alias(en TEXT PRIMARY KEY, zh TEXT) ON COMMIT DROP;
    INSERT INTO _zh_alias(en, zh) VALUES
        ('pcs','件'), ('pc','件'), ('piece','件'), ('pieces','件'),
        ('portion','份'), ('slice','片'), ('sheet','张'), ('item','项'),
        ('box','盒'), ('case','箱'), ('carton','箱'), ('bag','袋'), ('pack','包'),
        ('bottle','瓶'), ('can','罐'), ('crate','框'), ('pail','桶'),
        ('roll','卷'), ('tray','托盘'), ('plate','板');

    -- ── 原料基本单位 ──────────────────────────────────────────────────────
    INSERT INTO backup_sku_units_zh_20260814(table_name, row_id, column_name, old_value)
    SELECT 'raw_material_types', t.id, 'unit', t.unit
    FROM raw_material_types t JOIN _zh_alias a ON a.en = lower(t.unit)
    WHERE t.deleted_at IS NULL;

    UPDATE raw_material_types t SET unit = a.zh
    FROM _zh_alias a WHERE a.en = lower(t.unit) AND t.deleted_at IS NULL;

    -- ── 成品基本单位 ──────────────────────────────────────────────────────
    INSERT INTO backup_sku_units_zh_20260814(table_name, row_id, column_name, old_value)
    SELECT 'product_types', t.id, 'unit', t.unit
    FROM product_types t JOIN _zh_alias a ON a.en = lower(t.unit)
    WHERE t.deleted_at IS NULL;

    UPDATE product_types t SET unit = a.zh
    FROM _zh_alias a WHERE a.en = lower(t.unit) AND t.deleted_at IS NULL;

    -- ── 原料包装规格 ──────────────────────────────────────────────────────
    INSERT INTO backup_sku_units_zh_20260814(table_name, row_id, column_name, old_value)
    SELECT 'material_packaging_specs', s.id, 'package_unit', s.package_unit
    FROM material_packaging_specs s JOIN _zh_alias a ON a.en = lower(s.package_unit)
    WHERE s.deleted_at IS NULL;

    UPDATE material_packaging_specs s SET package_unit = a.zh
    FROM _zh_alias a WHERE a.en = lower(s.package_unit) AND s.deleted_at IS NULL;

    -- ── 成品包装规格 ──────────────────────────────────────────────────────
    INSERT INTO backup_sku_units_zh_20260814(table_name, row_id, column_name, old_value)
    SELECT 'product_packaging_specs', s.id, 'package_unit', s.package_unit
    FROM product_packaging_specs s JOIN _zh_alias a ON a.en = lower(s.package_unit)
    WHERE s.deleted_at IS NULL;

    UPDATE product_packaging_specs s SET package_unit = a.zh
    FROM _zh_alias a WHERE a.en = lower(s.package_unit) AND s.deleted_at IS NULL;

    -- ── 工厂单位目录 (unit_of_measurements.unit_code) ────────────────────
    -- 不补这张表, 工厂用旧英文码注册的单位会从「内置」掉成「自定义」:
    -- storageUnit 查不到 SYSTEM_UNITS 里的 box, 于是走规则 2b 返回工厂填的显示名,
    -- 同一个单位在不同工厂落成不同的值。
    INSERT INTO backup_sku_units_zh_20260814(table_name, row_id, column_name, old_value)
    SELECT 'unit_of_measurements', u.id::text, 'unit_code', u.unit_code
    FROM unit_of_measurements u JOIN _zh_alias a ON a.en = lower(u.unit_code)
    WHERE u.deleted_at IS NULL;

    UPDATE unit_of_measurements u SET unit_code = a.zh
    FROM _zh_alias a WHERE a.en = lower(u.unit_code) AND u.deleted_at IS NULL;

    -- ⛔ 映射不出的原样留下, 点名报出来 —— 不猜
    FOR r IN
        SELECT 'raw_material_types' AS tbl, id, unit AS val FROM raw_material_types
         WHERE deleted_at IS NULL AND unit ~ '^[A-Za-z]{2,}$'
           AND lower(unit) NOT IN (SELECT en FROM _zh_alias)
           AND lower(unit) NOT IN ('kg','mg','ml','mm','cm','km','jin')
        UNION ALL
        SELECT 'product_types', id, unit FROM product_types
         WHERE deleted_at IS NULL AND unit ~ '^[A-Za-z]{2,}$'
           AND lower(unit) NOT IN (SELECT en FROM _zh_alias)
           AND lower(unit) NOT IN ('kg','mg','ml','mm','cm','km','jin')
    LOOP
        unmapped_count := unmapped_count + 1;
        RAISE NOTICE '未归一(映射不出, 需人工定): % id=% unit=%', r.tbl, r.id, r.val;
    END LOOP;

    RAISE NOTICE '中文单位码归一完成; 未归一 % 行', unmapped_count;
END $$;

-- =============================================================================
-- V20261029_54: 重拼规格串, 把里面的英文单位码换成中文展示名
--
-- 背景
--   客户 2026-08-06 报: 六膳门 BBQ猪五花 规格显示 `1kg/pack 10pack/箱 10kg/箱`,
--   一句里中英混排。根因在 ProductPackagingSpecServiceImpl#composeCanonicalSpecification
--   —— 它用 product_types.unit 直接拼, 而那列存的是**规范码**(V20261029_48 正是把它
--   统一成码的), package_unit 却是用户填的中文, 于是拼出一半码一半中文。
--   代码侧同批已修(改用 UnitDisplayNames), 但**存量行要等下次保存箱规才会重拼**,
--   这条迁移把存量一次性补齐。
--
-- ⛔ 只改「证明是机器拼的」那些行
--   2026-08-02 曾有过一次判断: 规格串是用户自由文本, 不该由代码改。那条结论对
--   **手填的**行仍然成立 —— 本迁移因此不做模糊替换, 判据是:
--
--       当前值 == 用**旧公式 + 原始码**重算出来的值
--
--   逐字相同才动它(那只可能是旧 composer 写的); 差一个字符就跳过, 交给人。
--   公式逐行对应 Java: massText / decimalText / 跳过 baseUnit==packageUnit 的箱规,
--   以及「先净含量, 再每条箱规两段」的顺序。
--
-- 影响面 (2026-08-06 prod cretas_prod_db 实测)
--   product_types 里规格串含英文码的 20 行 (LIUSHANMEN 8 / F006 12),
--   形状全部严格是 {净含量}/{码} {倍数}{码}/箱 {总量}/箱, 且 07-23 → 08-06 持续新增
--   —— 机器拼的, 不是历史手工录入。实际改几行以上面的逐字判据为准, 末尾 RAISE NOTICE。
--
-- 回滚
--   db/manual-rollback/V20261029_54__respell_product_specification_rollback.sql
--   (本迁移把改动前的值写进台账表 migration_spec_respell_20261029_54, 回滚从台账还原)
-- =============================================================================

CREATE TABLE IF NOT EXISTS migration_spec_respell_20261029_54 (
    product_type_id   varchar(64) PRIMARY KEY,
    factory_id        varchar(64)  NOT NULL,
    old_specification text         NOT NULL,
    new_specification text         NOT NULL,
    migrated_at       timestamp    NOT NULL DEFAULT now()
);

-- decimalText: BigDecimal.stripTrailingZeros().toPlainString()
-- ⚠️ 不能用 rtrim(x,'0') —— 整数 1000 会被剪成 1。trim_scale 只去小数尾零 (PG13+)。
CREATE OR REPLACE FUNCTION mig_54_num(v numeric) RETURNS text AS $$
    SELECT trim_scale(v)::text;
$$ LANGUAGE sql IMMUTABLE;

-- massText: >=1000g 进位成 kg, 与 Java 同一判据
CREATE OR REPLACE FUNCTION mig_54_mass(grams numeric) RETURNS text AS $$
    SELECT CASE WHEN grams >= 1000
                THEN mig_54_num(grams / 1000) || 'kg'
                ELSE mig_54_num(grams) || 'g'
           END;
$$ LANGUAGE sql IMMUTABLE;

-- 展示名表: 逐条抄自 UnitDisplayNames.COUNTING_DISPLAY。
-- ⚠️ 科学计量符号(kg/g/L/ml…)刻意不收 —— 认不出就原样返回, 与 Java 一致。
CREATE OR REPLACE FUNCTION mig_54_label(unit text) RETURNS text AS $$
    SELECT COALESCE(
        (SELECT m.label FROM (VALUES
            ('pcs','件'), ('portion','份'), ('box','盒'), ('case','箱'), ('bag','袋'),
            ('pack','包'), ('bottle','瓶'), ('can','罐'), ('crate','框'), ('pail','桶'),
            ('roll','卷'), ('slice','片'), ('sheet','张'), ('tray','托盘'),
            ('plate','板'), ('item','项')
         ) AS m(code, label)
         WHERE m.code = lower(btrim(COALESCE(unit, '')))),
        btrim(COALESCE(unit, '')));
$$ LANGUAGE sql IMMUTABLE;

-- composeCanonicalSpecification 的 SQL 复刻。
-- p_translate=false 还原**修复前**的输出(用于比对), =true 产出修复后的值。
CREATE OR REPLACE FUNCTION mig_54_compose(p_product_id text, p_translate boolean) RETURNS text AS $$
DECLARE
    v_grams   numeric;
    v_unit    text;
    v_factory text;
    v_base    text;
    v_pkg     text;
    v_parts   text[] := ARRAY[]::text[];
    r         record;
BEGIN
    SELECT grams_per_unit, btrim(COALESCE(unit, '')), factory_id
      INTO v_grams, v_unit, v_factory
      FROM product_types
     WHERE id = p_product_id;

    -- positive(): 非正数当作没有, 与 Java 一致
    IF v_grams IS NOT NULL AND v_grams <= 0 THEN
        v_grams := NULL;
    END IF;

    v_base := CASE WHEN p_translate THEN mig_54_label(v_unit) ELSE v_unit END;

    IF v_grams IS NOT NULL AND v_base <> '' THEN
        v_parts := v_parts || (mig_54_mass(v_grams) || '/' || v_base);
    END IF;

    FOR r IN
        SELECT conversion_factor, btrim(COALESCE(package_unit, '')) AS package_unit
          FROM product_packaging_specs
         WHERE factory_id = v_factory
           AND product_type_id = p_product_id
           AND is_active = true
           AND deleted_at IS NULL
         ORDER BY sort_order ASC, created_at ASC
    LOOP
        v_pkg := CASE WHEN p_translate THEN mig_54_label(r.package_unit) ELSE r.package_unit END;
        IF r.conversion_factor IS NOT NULL AND r.conversion_factor > 0
           AND v_base <> '' AND v_pkg <> '' AND v_base <> v_pkg THEN
            v_parts := v_parts || (mig_54_num(r.conversion_factor) || v_base || '/' || v_pkg);
            IF v_grams IS NOT NULL THEN
                v_parts := v_parts || (mig_54_mass(v_grams * r.conversion_factor) || '/' || v_pkg);
            END IF;
        END IF;
    END LOOP;

    RETURN array_to_string(v_parts, ' ');
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    v_changed  integer;
    v_skipped  integer;
    r          record;
BEGIN
    -- 先落台账(回滚要用), 只收「逐字等于旧公式输出」且新旧确实不同的行
    INSERT INTO migration_spec_respell_20261029_54
                (product_type_id, factory_id, old_specification, new_specification)
    SELECT p.id, p.factory_id, p.specification, mig_54_compose(p.id, true)
      FROM product_types p
     WHERE p.deleted_at IS NULL
       AND COALESCE(p.specification, '') <> ''
       AND p.specification = mig_54_compose(p.id, false)
       AND p.specification <> mig_54_compose(p.id, true)
    ON CONFLICT (product_type_id) DO NOTHING;

    UPDATE product_types p
       SET specification = l.new_specification,
           updated_at    = now()
      FROM migration_spec_respell_20261029_54 l
     WHERE p.id = l.product_type_id
       AND p.specification = l.old_specification;
    GET DIAGNOSTICS v_changed = ROW_COUNT;

    -- 含英文码但**不是**旧公式产物的行: 不动, 点名交人工
    SELECT count(*) INTO v_skipped
      FROM product_types p
     WHERE p.deleted_at IS NULL
       AND COALESCE(p.specification, '') <> ''
       AND p.specification ~ '(^|[^A-Za-z])(pcs|portion|box|case|bag|pack|bottle|can|crate|pail|roll|slice|sheet|tray|plate|item)(/|\s|$)'
       AND p.specification <> mig_54_compose(p.id, false);

    RAISE NOTICE 'V20261029_54: 重拼 % 行规格串', v_changed;
    IF v_skipped > 0 THEN
        RAISE NOTICE 'V20261029_54: 另有 % 行含英文码但不是旧公式产物, 已跳过(疑似人工录入, 交人工核):', v_skipped;
        FOR r IN
            SELECT p.factory_id, p.code, p.specification
              FROM product_types p
             WHERE p.deleted_at IS NULL
               AND COALESCE(p.specification, '') <> ''
               AND p.specification ~ '(^|[^A-Za-z])(pcs|portion|box|case|bag|pack|bottle|can|crate|pail|roll|slice|sheet|tray|plate|item)(/|\s|$)'
               AND p.specification <> mig_54_compose(p.id, false)
             ORDER BY p.factory_id, p.code
        LOOP
            RAISE NOTICE '  跳过 %/% : %', r.factory_id, r.code, r.specification;
        END LOOP;
    END IF;
END $$;

DROP FUNCTION IF EXISTS mig_54_compose(text, boolean);
DROP FUNCTION IF EXISTS mig_54_label(text);
DROP FUNCTION IF EXISTS mig_54_mass(numeric);
DROP FUNCTION IF EXISTS mig_54_num(numeric);

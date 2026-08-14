-- =============================================================================
-- V20261029_86: 把 V20261029_85 漏掉的单位列一起归一到中文码
--
-- 为什么有第二发
--   V85 手写了 5 条 UPDATE, 覆盖 raw_material_types.unit / product_types.unit /
--   material_packaging_specs.package_unit / product_packaging_specs.package_unit /
--   unit_of_measurements.unit_code。
--
--   2026-08-14 全库扫描(109 个存单位的列)实测: 仍有 429 行英文码分布在 12 张表 /
--   17 个列上, 其中 **同一张表的另一个单位列** 就漏了三处 ——
--     material_packaging_specs.base_unit  (box 3 / slice 2 / roll 1)
--     product_packaging_specs.base_unit   (box 17 / bag 3 / pack 1)
--     unit_of_measurements.base_unit      (pcs 16)
--   剩下的是 8 张压根没碰的表: bom_recipes / bom_recipe_items / work_processes /
--   material_packaging_hierarchy / sales_order_items / sales_delivery_items /
--   supplier_materials / purchase_exceptions / product_unit_conversions。
--
--   用户看得见英文的地方 —— BOM 明细、工序配置、销售订单、发货单、供应商采购单位、
--   包装层级 —— V85 一个都没覆盖到。
--
-- 🔴 product_unit_conversions 不只是好看不好看的问题
--   ProductSpecificationConversionSyncServiceImpl.upsert() 第 97-98 行用**字面**
--   equals 比对 normalize() 出来的码(V85 之后是中文)与库里存的码(仍是英文):
--       from.equals(relation.getFromUnitCode()) && to.equals(relation.getToUnitCode())
--   永远不等 → occupied 恒为 null → 紧挨着的那道
--   「SKU 规格与现有手工单位换算冲突」(409, SKU_SPEC_CONVERSION_CONFLICT)
--   再也触发不了, 手工换算与规格换算可以无声共存。
--   2026-08-14 实测目前还没坏(重复对 0 / 系数冲突 0 / 中英混存产品 0) —— 是**潜伏**,
--   不是已发生。归一之后那道闸才重新武装。
--
-- ⛔ 为什么改成动态遍历而不是再手列一遍
--   V85 的失效方式就是「手写清单漏了 12 张表」。再手写一遍只会换个地方漏。
--   这里从 information_schema 遍历所有单位列, 覆盖面不再依赖我记得多少张表。
--   动作范围由**值**兜底: 只有 lower(值) 命中下面 20 条别名才会被改, 其余一律不动 ——
--   所以即使遍历到了某个语义不是单位的列, 它的值也不可能被误改。
--
-- ⚠️ 科学换算单位 (kg/g/mg/t/ml/l/mm/cm/m/km/celsius/percent/minute/...) 不在范围内,
--   与 V85 同一条界线: 它们有恒定换算且符号是国际写法。别名表里没有它们, 所以碰不到。
--
-- ⛔ 映射不出的不猜 (ton / jin / liang / unitless 等) —— 原样留下并 RAISE NOTICE 点名。
--
-- 回滚
--   备份表 backup_sku_units_zh2_20260814 逐行记了 (表, 行id, 列, 原值), 反向 UPDATE 即可。
-- =============================================================================

CREATE TABLE IF NOT EXISTS backup_sku_units_zh2_20260814 (
    table_name   VARCHAR(64),
    row_id       VARCHAR(64),
    column_name  VARCHAR(64),
    old_value    VARCHAR(64),
    backed_up_at TIMESTAMP DEFAULT NOW()
);

DO $$
DECLARE
    col            RECORD;
    r              RECORD;
    touched_cols   INT := 0;
    touched_rows   INT := 0;
    n              BIGINT;
    del_guard      TEXT;
    leftover       INT := 0;
    unmapped       INT := 0;
BEGIN
    -- 与 UnitContractServiceImpl.systemAliases() 保持一致; 与 V20261029_85 同一份表。
    -- ⚠️ 改权威表必须同步改这里(反之亦然)。
    CREATE TEMP TABLE _zh_alias(en TEXT PRIMARY KEY, zh TEXT) ON COMMIT DROP;
    INSERT INTO _zh_alias(en, zh) VALUES
        ('pcs','件'), ('pc','件'), ('piece','件'), ('pieces','件'),
        ('portion','份'), ('slice','片'), ('sheet','张'), ('item','项'),
        ('box','盒'), ('case','箱'), ('carton','箱'), ('bag','袋'), ('pack','包'),
        ('bottle','瓶'), ('can','罐'), ('crate','框'), ('pail','桶'),
        ('roll','卷'), ('tray','托盘'), ('plate','板');

    -- 待处理的单位列: 名字像单位 + 类型是文本 + 是实体表 + 不是备份/修复表。
    CREATE TEMP TABLE _unit_cols AS
    SELECT c.table_name,
           c.column_name,
           EXISTS (SELECT 1 FROM information_schema.columns d
                    WHERE d.table_schema = 'public'
                      AND d.table_name = c.table_name
                      AND d.column_name = 'deleted_at') AS has_soft_delete,
           EXISTS (SELECT 1 FROM information_schema.columns d
                    WHERE d.table_schema = 'public'
                      AND d.table_name = c.table_name
                      AND d.column_name = 'id') AS has_id
      FROM information_schema.columns c
      JOIN information_schema.tables t
        ON t.table_schema = c.table_schema
       AND t.table_name = c.table_name
       AND t.table_type = 'BASE TABLE'
     WHERE c.table_schema = 'public'
       AND (c.column_name = 'unit'
            OR c.column_name LIKE '%\_unit'
            OR c.column_name LIKE 'unit\_%'
            OR c.column_name LIKE '%\_unit\_%')
       AND c.data_type IN ('character varying', 'text')
       AND c.table_name !~ '^(backup_|bak_|repair_|tmp_)';

    -- 🔴 先探一次: 归一会不会把 product_unit_conversions 撞成重复的有效换算。
    -- 撞了就整个迁移失败, 不要留下一半归一一半没归一的库。
    SELECT count(*) INTO n FROM (
        SELECT c.factory_id, c.product_type_id,
               COALESCE((SELECT zh FROM _zh_alias a WHERE a.en = lower(c.from_unit_code)),
                        c.from_unit_code) AS f,
               COALESCE((SELECT zh FROM _zh_alias a WHERE a.en = lower(c.to_unit_code)),
                        c.to_unit_code) AS t2
          FROM product_unit_conversions c
         WHERE c.deleted_at IS NULL
         GROUP BY 1,2,3,4
        HAVING count(*) > 1
    ) z;
    IF n > 0 THEN
        RAISE EXCEPTION
            '归一后 product_unit_conversions 会出现 % 组重复的有效换算, 已中止。请先人工合并这些换算再重跑。', n;
    END IF;

    -- ── 逐列归一 ──────────────────────────────────────────────────────────
    FOR col IN SELECT * FROM _unit_cols ORDER BY table_name, column_name LOOP
        del_guard := CASE WHEN col.has_soft_delete THEN ' AND t.deleted_at IS NULL' ELSE '' END;

        IF col.has_id THEN
            EXECUTE format(
                'INSERT INTO backup_sku_units_zh2_20260814(table_name, row_id, column_name, old_value)
                 SELECT %L, t.id::text, %L, t.%I
                   FROM %I t JOIN _zh_alias a ON a.en = lower(t.%I)
                  WHERE TRUE%s',
                col.table_name, col.column_name, col.column_name,
                col.table_name, col.column_name, del_guard);
        ELSE
            RAISE NOTICE '⚠ %.% 所在表没有 id 列, 本列不做逐行备份(仍会归一)',
                col.table_name, col.column_name;
        END IF;

        EXECUTE format(
            'UPDATE %I t SET %I = a.zh FROM _zh_alias a
              WHERE a.en = lower(t.%I)%s',
            col.table_name, col.column_name, col.column_name, del_guard);
        GET DIAGNOSTICS n = ROW_COUNT;

        IF n > 0 THEN
            touched_cols := touched_cols + 1;
            touched_rows := touched_rows + n;
            RAISE NOTICE '归一 %.% : % 行', col.table_name, col.column_name, n;
        END IF;
    END LOOP;

    -- ── 映射不出的点名 (不猜) ─────────────────────────────────────────────
    FOR col IN SELECT * FROM _unit_cols ORDER BY table_name, column_name LOOP
        del_guard := CASE WHEN col.has_soft_delete THEN ' AND t.deleted_at IS NULL' ELSE '' END;
        FOR r IN EXECUTE format(
            'SELECT %L::text AS tbl, %L::text AS col, t.%I::text AS val, count(*)::bigint AS n
               FROM %I t
              WHERE t.%I ~ ''^[A-Za-z][A-Za-z0-9_/]*$''
                AND lower(t.%I) NOT IN (SELECT en FROM _zh_alias)
                AND lower(t.%I) NOT IN
                    (''kg'',''g'',''mg'',''t'',''l'',''ml'',''kl'',''m'',''cm'',''mm'',''km'',''m2'',''m3'',
                     ''mg/kg'',''g/kg'',''ppm'',''percent'',''permille'',''celsius'',''fahrenheit'',
                     ''second'',''minute'',''hour'',''day'',''min'',''h'')%s
              GROUP BY 3',
            col.table_name, col.column_name, col.column_name,
            col.table_name, col.column_name, col.column_name, col.column_name, del_guard)
        LOOP
            unmapped := unmapped + 1;
            RAISE NOTICE '未归一(映射不出, 需人工定): %.% = % (% 行)', r.tbl, r.col, r.val, r.n;
        END LOOP;
    END LOOP;

    -- ── 自检: 还有没有可映射的英文码活着 ──────────────────────────────────
    -- V85 的失效方式是「以为覆盖完了」。这里让迁移自己回答, 而不是靠我记得。
    FOR col IN SELECT * FROM _unit_cols ORDER BY table_name, column_name LOOP
        del_guard := CASE WHEN col.has_soft_delete THEN ' AND t.deleted_at IS NULL' ELSE '' END;
        EXECUTE format(
            'SELECT count(*) FROM %I t
              WHERE lower(t.%I) IN (SELECT en FROM _zh_alias)%s',
            col.table_name, col.column_name, del_guard) INTO n;
        IF n > 0 THEN
            leftover := leftover + n;
            RAISE WARNING '自检未通过: %.% 仍有 % 行可映射英文码', col.table_name, col.column_name, n;
        END IF;
    END LOOP;

    IF leftover > 0 THEN
        RAISE EXCEPTION '归一自检未通过: 仍有 % 行可映射英文码未被改写, 已回滚。', leftover;
    END IF;

    RAISE NOTICE '中文单位码补齐完成: % 个列 / % 行已归一; 映射不出并点名 % 类',
        touched_cols, touched_rows, unmapped;

    DROP TABLE _unit_cols;
END $$;

-- P4 历史成品库存 kg→盒 换算迁移
-- 将 F006 finished_goods_batches 中 unit='kg' 且对应产品 unit='盒' 且 gramsPerUnit 已配置的行换算为盒
-- 注意: 本迁移在 V20260915_03 (seed gramsPerUnit) 之后运行, gramsPerUnit 已存在
-- 不可逆: remark 保留原 kg 值 "[P4]原kg=X"

-- Pre-flight guard: 表存在检查 (entity-only 表在 fresh-DB 不存在)
DO $$
BEGIN
    -- finished_goods_batches 由 V20260415_99 建立 (非 entity-only), 正常情况表已存在.
    -- 用 to_regclass 防御, 避免 fresh-DB 迁移时因顺序问题崩溃.
    IF to_regclass('public.finished_goods_batches') IS NULL THEN
        RAISE NOTICE 'V20260915_04: finished_goods_batches 不存在, 跳过迁移 (fresh-DB)';
        RETURN;
    END IF;
    IF to_regclass('public.product_types') IS NULL THEN
        RAISE NOTICE 'V20260915_04: product_types 不存在, 跳过迁移 (fresh-DB)';
        RETURN;
    END IF;
END $$;

-- Preview: 受影响行数统计
DO $$
DECLARE
    affected_count INT;
BEGIN
    IF to_regclass('public.finished_goods_batches') IS NULL THEN RETURN; END IF;

    SELECT COUNT(*) INTO affected_count
      FROM finished_goods_batches fgb
      JOIN product_types pt ON pt.id = fgb.product_type_id
     WHERE fgb.factory_id = 'F006'
       AND fgb.unit = 'kg'
       AND pt.unit = '盒'
       AND pt.grams_per_unit IS NOT NULL
       AND pt.grams_per_unit > 0;

    RAISE NOTICE 'V20260915_04: % 行 finished_goods_batches 需要 kg→盒换算', affected_count;
END $$;

-- 主迁移: kg → 盒换算 (UPDATE, 不可逆)
DO $$
BEGIN
    IF to_regclass('public.finished_goods_batches') IS NULL THEN RETURN; END IF;

    UPDATE finished_goods_batches fgb
       SET produced_quantity  = ROUND(fgb.produced_quantity  * 1000 / pt.grams_per_unit, 2),
           shipped_quantity   = ROUND(fgb.shipped_quantity   * 1000 / pt.grams_per_unit, 2),
           reserved_quantity  = ROUND(fgb.reserved_quantity  * 1000 / pt.grams_per_unit, 2),
           unit               = '盒',
           remark             = COALESCE(fgb.remark || ' | ', '') || '[P4]原kg=' || fgb.produced_quantity::TEXT
                                 || ', gramsPerUnit=' || pt.grams_per_unit::TEXT
      FROM product_types pt
     WHERE fgb.product_type_id = pt.id
       AND fgb.factory_id = 'F006'
       AND fgb.unit = 'kg'
       AND pt.unit = '盒'
       AND pt.grams_per_unit IS NOT NULL
       AND pt.grams_per_unit > 0;

    RAISE NOTICE 'V20260915_04: 历史 FG kg→盒换算完成, % 行更新', ROW_COUNT();
END $$;

-- Post-verify: 确认无遗留 kg 行 (产品 unit=盒)
DO $$
DECLARE
    remaining_kg INT;
BEGIN
    IF to_regclass('public.finished_goods_batches') IS NULL THEN RETURN; END IF;

    SELECT COUNT(*) INTO remaining_kg
      FROM finished_goods_batches fgb
      JOIN product_types pt ON pt.id = fgb.product_type_id
     WHERE fgb.factory_id = 'F006'
       AND fgb.unit = 'kg'
       AND pt.unit = '盒'
       AND pt.grams_per_unit IS NOT NULL;

    IF remaining_kg > 0 THEN
        RAISE WARNING 'V20260915_04: 仍有 % 行 F006 FG unit=kg 但产品 unit=盒, 需人工检查', remaining_kg;
    ELSE
        RAISE NOTICE 'V20260915_04: 验证通过, F006 无遗留 kg FG (产品 unit=盒)';
    END IF;
END $$;

-- =============================================================================
-- V20261029_51: 带鱼两条批次的单位「箱」→ kg (V20261029_50 留下的 [待人工] 尾巴)
--
-- 背景
--   V20261029_50 跑完后, 档案 vs 批次的单位混写从 11 行降到 3 行, 剩下的都是
--   「档案 kg / 批次 箱」这种<b>量纲不一致</b>, 迁移刻意只报不改(改错会让「5 箱」变「5 公斤」)。
--   逐行查证后这 3 行<b>分成两类, 不能一刀切</b>:
--
--   ✅ 本迁移处理 —— 带鱼 (F001 / DEMO_FACTORY, 同一测试批次号 MB-TEST-20260102-001):
--        material_packaging_specs 里<b>没有任何 箱→? 的换算</b>, 「箱」在这个物料上无定义。
--        旁证: 带鱼共 26 条批次用 kg (合计 2518), 只有这 2 条用「箱」(各 124.5);
--        124.5 落在 kg 那 26 条的量级里(均值约 97), 说明本就是 kg 被误填成「箱」。
--
--   ⛔ 本迁移<b>不动</b> —— SHH0713羊排 (F006, MT-20260716-3809):
--        material_packaging_specs 有 package_unit=箱 / base_unit=kg / conversion_factor=10,
--        即 1 箱 = 10 kg。该批 10 箱 = <b>100 kg</b>, 正对得上交接记的
--        「F006 羊排原料仓 100kg 全过期」。
--        按「箱是错误单位」把它改成 kg, 100kg 会静默变成 10kg, 凭空蒸发 90kg。
--        <b>包装单位存量是合法的, 不是缺陷。</b>
--
-- 为什么写死行而不是写动态谓词
--   一次性清理用「写死 id + 值守卫」优于「按状态动态判定」—— 2026-08-01 的
--   V20261029_44 实测过: 动态谓词会在部署前状态变化时翻面, 把本该保留的行删掉。
--   这里同理: 若将来给带鱼补了 箱→kg 规格, 动态谓词(按"有没有规格"判)就会改变行为,
--   而写死的两行 + 值守卫只会安静跳过。
--
-- 影响面: 2 行, 均 EXPIRED (过期日 2026-07-01), 不影响可用库存。
--   数量不动, 只改单位字面值 —— 124.5「箱」→ 124.5 kg。
--
-- 回滚
--   db/manual-rollback/V20261029_51__daiyu_batch_unit_box_to_kg_rollback.sql
-- =============================================================================

DO $$
DECLARE
    v_fixed   INT := 0;
    v_skipped INT := 0;
    rec       RECORD;
BEGIN
    FOR rec IN
        SELECT * FROM (VALUES
            ('F001',         'MB-TEST-20260102-001'),
            ('DEMO_FACTORY', 'MB-TEST-20260102-001')
        ) AS t(factory_id, batch_number)
    LOOP
        -- 值守卫: 只在「档案 kg / 批次 箱 / 该物料确实没有 箱→? 规格」三条都成立时才改。
        -- 任何一条不成立(被人先改过 / 后来补了包装规格)就跳过并报出来, 不猜。
        UPDATE material_batches b
        SET quantity_unit = 'kg', updated_at = NOW()
        FROM raw_material_types rt
        WHERE rt.id = b.material_type_id
          AND b.factory_id = rec.factory_id
          AND b.batch_number = rec.batch_number
          AND b.quantity_unit = '箱'
          AND rt.unit = 'kg'
          AND NOT EXISTS (
                SELECT 1 FROM material_packaging_specs s
                WHERE s.material_type_id = b.material_type_id
                  AND s.deleted_at IS NULL
                  AND s.package_unit = '箱');

        IF FOUND THEN
            v_fixed := v_fixed + 1;
        ELSE
            v_skipped := v_skipped + 1;
            RAISE NOTICE 'V20261029_51 [跳过] %/% 当前状态不满足守卫(档案kg + 批次箱 + 无箱规格), 未改动',
                rec.factory_id, rec.batch_number;
        END IF;
    END LOOP;

    RAISE NOTICE 'V20261029_51 带鱼批次单位 箱→kg: 已改 % 行, 跳过 % 行', v_fixed, v_skipped;
END $$;

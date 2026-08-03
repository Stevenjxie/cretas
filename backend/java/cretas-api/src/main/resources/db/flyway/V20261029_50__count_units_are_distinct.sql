-- =============================================================================
-- V20261029_50: 只 / 个 / 件 是三个单位 —— 撤销 V20261029_48 对计数单位的合并
--
-- Steve 2026-08-03 拍板:「算两个单位」(只 ≠ 件)。
--
-- 为什么只撤计数那一批, 不整条回滚 V20261029_48
--   V20261029_48 把所有中文单位归一成码, 其中两类性质完全不同:
--     · 盒→box / 箱→case / 袋→bag …  一个码对<b>一个</b>中文写法 = 纯翻译, 无信息损失
--     · 件/个/只 → pcs                一个码对<b>三个</b>中文写法 = 替工厂断定三者相同
--   Steve 同日另一条拍板是「存量不动」, 所以前一类保持存码; 本迁移只撤后一类。
--   判据写成 new_unit = 'pcs', 不是列 id —— 数据驱动, 与备份台账一致。
--
-- 与 #1976 的关系
--   #1976 / TransferUnitCanonicalizationTest / 报工侧 canonicalNativeUnit 一直主张
--   「一只不等于一件」, 而 V20261029_48 把档案合并成 pcs —— 两者方向相反且都在 main 上。
--   本迁移让<b>数据</b>站到 #1976 那一侧, 与同批代码改动 (storageUnit 落库保字面) 同向。
--
--   ⚠️ 作用域仅限「数量 / 库存」。Workflow <b>槽位匹配</b>侧
--   (BomWorkflowRevisionService#canonicalUnit → canonicalCodeOrRaw) <b>刻意</b>仍把
--   件/个/只 折成 pcs, 因为它判的是「这个投入槽还在不在」, 本就要认本地化写法。别一起改。
--
-- 顺序要求 (已由 Flyway 天然满足)
--   数据与写入侧必须同向, 否则「修好又漂回去」(V20261029_32 的老毛病)。
--   Flyway 在服务启动时先跑, 新代码后服务流量 —— 同一次发布即可。
--
-- 影响面 (2026-08-02 prod 实测, 见 V20261029_48 自身注释)
--   raw_material_types: 个67 + 只3 + 件2 = 72 行被合并成 pcs, 本迁移还原
--   material_batches  : 现有 件3 / 个2 且<b>一条 pcs 批次都没有</b> ——
--                       还原后档案与这 5 行批次自动对上 (原「档案 pcs vs 批次 件/个」的混写)
--
-- 回滚
--   db/manual-rollback/V20261029_50__count_units_are_distinct_rollback.sql
-- =============================================================================

DO $$
DECLARE
    v_raw     INT := 0;
    v_product INT := 0;
    v_missing INT := 0;
BEGIN
    -- 台账不存在就整条跳过 —— 老库/新库没跑过 V20261029_48 时不该报错
    IF to_regclass('public.backup_sku_units_20260802') IS NULL THEN
        RAISE NOTICE 'V20261029_50: 备份台账不存在, 跳过 (该库未执行过 V20261029_48)';
        RETURN;
    END IF;

    -- ⚠️ 只还原「当前值仍等于迁移当初写入的值」的行。
    -- 迁移之后有人手工改过的, 那是比台账更新的事实, 不覆盖。
    UPDATE raw_material_types t
    SET unit = b.old_unit, updated_at = NOW()
    FROM backup_sku_units_20260802 b
    WHERE b.table_name = 'raw_material_types'
      AND b.row_id = t.id
      AND b.new_unit = 'pcs'          -- 只撤计数那一批
      AND t.unit = b.new_unit;        -- 期间没被人改过
    GET DIAGNOSTICS v_raw = ROW_COUNT;

    UPDATE product_types t
    SET unit = b.old_unit, updated_at = NOW()
    FROM backup_sku_units_20260802 b
    WHERE b.table_name = 'product_types'
      AND b.row_id = t.id
      AND b.new_unit = 'pcs'
      AND t.unit = b.new_unit;
    GET DIAGNOSTICS v_product = ROW_COUNT;

    -- 台账里 new_unit='pcs' 但当前值已被改过的, 报出来供人工核对(不覆盖)
    SELECT count(*) INTO v_missing
    FROM backup_sku_units_20260802 b
    WHERE b.new_unit = 'pcs'
      AND NOT EXISTS (
            SELECT 1 FROM raw_material_types t
            WHERE t.id = b.row_id AND b.table_name = 'raw_material_types' AND t.unit = b.old_unit)
      AND NOT EXISTS (
            SELECT 1 FROM product_types p
            WHERE p.id = b.row_id AND b.table_name = 'product_types' AND p.unit = b.old_unit);

    RAISE NOTICE 'V20261029_50 还原计数单位: raw_material_types=% 行, product_types=% 行, 期间被改过未覆盖=% 行',
        v_raw, v_product, v_missing;
END $$;

-- ---------------------------------------------------------------------------
-- 批次侧: 纯翻译型混写 —— 批次存中文而档案存对应的码, 改批次跟随档案
--
-- ⛔ 只处理<b>同一个单位的两种写法</b>(盒/box、箱/case、片/slice …), 即档案的码就是
--    该中文写法的权威码。这类改动不动数量含义。
-- ⛔ <b>不</b>处理量纲不一致的(如档案 kg 而批次「箱」) —— 那不是写法差异, 改了会让
--    「5 箱」变成「5 公斤」。下面单独报出来交人工。
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE _zh_to_code(zh TEXT PRIMARY KEY, code TEXT);
INSERT INTO _zh_to_code VALUES
 ('毫克','mg'),('克','g'),('公斤','kg'),('千克','kg'),('斤','jin'),('吨','t'),
 ('毫升','ml'),('升','l'),
 ('份','portion'),('盒','box'),('箱','case'),('袋','bag'),('包','pack'),
 ('瓶','bottle'),('罐','can'),('框','crate'),('筐','crate'),('桶','pail'),
 ('卷','roll'),('片','slice'),('张','sheet'),('托盘','tray'),('板','plate'),('项','item');
-- 刻意不含 件/个/只 —— 它们正是本迁移要还原成中文的那批, 不能反手又折成码。

DO $$
DECLARE
    v_batch INT := 0;
    r RECORD;
BEGIN
    UPDATE material_batches b
    SET quantity_unit = r.unit, updated_at = NOW()
    FROM raw_material_types r, _zh_to_code m
    WHERE r.id = b.material_type_id
      AND b.deleted_at IS NULL AND r.deleted_at IS NULL
      AND b.quantity_unit = m.zh          -- 批次存中文
      AND r.unit = m.code;                -- 档案存的正是它的权威码 → 同一个单位
    GET DIAGNOSTICS v_batch = ROW_COUNT;
    RAISE NOTICE 'V20261029_50 批次单位跟随档案(纯翻译型): % 行', v_batch;

    -- 量纲对不上的, 只报不改
    FOR r IN
        SELECT b.factory_id, b.batch_number, rt.name AS material,
               rt.unit AS archive_unit, b.quantity_unit AS batch_unit, b.receipt_quantity
        FROM material_batches b JOIN raw_material_types rt ON rt.id = b.material_type_id
        WHERE b.deleted_at IS NULL AND rt.deleted_at IS NULL
          AND b.quantity_unit IS DISTINCT FROM rt.unit
          AND (b.quantity_unit ~ '[一-龥]' OR rt.unit ~ '[一-龥]')
    LOOP
        RAISE NOTICE 'V20261029_50 [待人工] %/% 物料=% 档案单位=% 批次单位=% 数量=%',
            r.factory_id, r.batch_number, r.material, r.archive_unit, r.batch_unit, r.receipt_quantity;
    END LOOP;
END $$;

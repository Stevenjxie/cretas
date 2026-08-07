-- =============================================================================
-- V20261029_66: 六膳门「1斤=1kg」假换算订正 —— 删掉假规格 + 单据单位标签归位
--
-- 背景
--   客户报「包装单位不能与库存基本单位相同」卡住保存, 并说明
--   「牛肉入仓都是抄码的, 他没有固定重量一箱」。
--   前端当时强制至少一条包装换算、第一条又删不掉, 于是用户编了一条假的:
--     raw_material_types.unit = 'kg'
--     material_packaging_specs: package_unit='jin', conversion_factor=1
--   —— 物理上 1 斤 = 0.5kg, 这条换算是错的。
--   (前端那三条规则已由 PR#2341 改掉: 包装换算现在可选、第一条可删。)
--
-- 🔴 为什么**绝不能**把 factor 改成正确的 0.5
--   prod 实测三处的数与单位:
--     purchase_order_items    quantity=995.75  unit='jin'
--     material_batches        receipt_quantity=995.75  quantity_unit='kg'
--     客户 Excel 台账列头      「初期重量KG」   995.75
--   真实值就是 **995.75 kg**。数字之所以对, 正是因为这条假换算 1:1 ——
--   **两个错误互相抵消**。把 factor 改成 0.5, 995.75 会折成 497.875,
--   库存当场少一半。所以订正的是**标签**, 不是数值。
--
-- 改什么
--   1. 删掉那条假包装规格(软删) → 以后新建采购单不会再选到「斤」
--   2. purchase_order_items / purchase_receive_items 的 unit(及 price_unit)
--      'jin' → 'kg', **数量与单价一个都不动**
--      (unit_price=1.0000 是占位价; factor=1 时 jin/kg 数值等价, 金额不变)
--
-- ⛔ 不改什么
--   - material_batches: quantity_unit 本来就是 'kg', 数值也对 —— 一个字段都不碰
--   - purchase_receive_items 的三个快照列
--     (inventory_base_unit_snapshot='kg' / package_to_base_factor_snapshot=1 /
--      inventory_quantity_snapshot=995.75) —— 那是**不可变的收货换算快照**,
--     且本来就是正确的 kg 口径, 改它才是改写历史
--   - 其它工厂、其它物料: 六膳门另外 4 条包装规格(9.5箱/10箱/10箱/1000吨)全是对的
--
-- 判据(只改证明是这条假换算产物的行)
--   物料 = 那一个 SKU, 且 unit='jin', 且收货快照的基本单位是 'kg' 而 factor=1。
--   多一个条件都不满足就跳过。
--
-- 回滚
--   db/manual-rollback/V20261029_66__liushanmen_fake_jin_unit_correction_rollback.sql
--   (本迁移把改动前的值写进台账 migration_jin_unit_fix_20261029_66)
-- =============================================================================

CREATE TABLE IF NOT EXISTS migration_jin_unit_fix_20261029_66 (
    id           bigserial PRIMARY KEY,
    entity       varchar(64)  NOT NULL,   -- packaging_spec / purchase_order_item / purchase_receive_item
    entity_id    varchar(64)  NOT NULL,
    field        varchar(64)  NOT NULL,
    old_value    text,
    new_value    text,
    migrated_at  timestamp    NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_material_id text;
    v_spec        integer := 0;
    v_poi         integer := 0;
    v_pri         integer := 0;
BEGIN
    SELECT m.id INTO v_material_id
      FROM raw_material_types m
     WHERE m.factory_id = 'LIUSHANMEN'
       AND m.unit = 'kg'
       AND EXISTS (
             SELECT 1 FROM material_packaging_specs s
              WHERE s.material_type_id = m.id
                AND s.deleted_at IS NULL
                AND lower(btrim(s.package_unit)) = 'jin'
                AND s.conversion_factor = 1)
     LIMIT 1;

    IF v_material_id IS NULL THEN
        RAISE NOTICE 'V20261029_66: 未找到「1斤=1kg」的假包装规格, 无需订正(可能已手工处理)';
        RETURN;
    END IF;

    -- ① 假包装规格 → 软删
    INSERT INTO migration_jin_unit_fix_20261029_66 (entity, entity_id, field, old_value, new_value)
    SELECT 'packaging_spec', s.id, 'deleted_at', NULL, 'now()'
      FROM material_packaging_specs s
     WHERE s.material_type_id = v_material_id
       AND s.deleted_at IS NULL
       AND lower(btrim(s.package_unit)) = 'jin'
       AND s.conversion_factor = 1;

    UPDATE material_packaging_specs s
       SET deleted_at = now(), updated_at = now()
     WHERE s.material_type_id = v_material_id
       AND s.deleted_at IS NULL
       AND lower(btrim(s.package_unit)) = 'jin'
       AND s.conversion_factor = 1;
    GET DIAGNOSTICS v_spec = ROW_COUNT;

    -- ② 采购单行: 单位标签归位, 数量/单价不动
    INSERT INTO migration_jin_unit_fix_20261029_66 (entity, entity_id, field, old_value, new_value)
    SELECT 'purchase_order_item', poi.id::text, 'unit', poi.unit, 'kg'
      FROM purchase_order_items poi
      JOIN purchase_orders po ON po.id = poi.purchase_order_id
     WHERE po.factory_id = 'LIUSHANMEN'
       AND poi.material_type_id = v_material_id
       AND lower(btrim(poi.unit)) = 'jin';

    UPDATE purchase_order_items poi
       SET unit = 'kg', updated_at = now()
      FROM purchase_orders po
     WHERE po.id = poi.purchase_order_id
       AND po.factory_id = 'LIUSHANMEN'
       AND poi.material_type_id = v_material_id
       AND lower(btrim(poi.unit)) = 'jin';
    GET DIAGNOSTICS v_poi = ROW_COUNT;

    -- ③ 收货行: 同上。⛔ 三个快照列一个都不碰(本来就是 kg 口径且正确)
    INSERT INTO migration_jin_unit_fix_20261029_66 (entity, entity_id, field, old_value, new_value)
    SELECT 'purchase_receive_item', pri.id::text, 'unit/price_unit',
           pri.unit || '/' || COALESCE(pri.price_unit, ''), 'kg/kg'
      FROM purchase_receive_items pri
      JOIN purchase_receive_records r ON r.id = pri.receive_record_id
     WHERE r.factory_id = 'LIUSHANMEN'
       AND pri.material_type_id = v_material_id
       AND lower(btrim(pri.unit)) = 'jin'
       -- 只动「快照证明本来就是 kg 且 1:1」的行 —— 这是它是那条假换算产物的证据
       AND pri.inventory_base_unit_snapshot = 'kg'
       AND pri.package_to_base_factor_snapshot = 1;

    UPDATE purchase_receive_items pri
       SET unit = 'kg',
           price_unit = CASE WHEN lower(btrim(pri.price_unit)) = 'jin' THEN 'kg' ELSE pri.price_unit END,
           updated_at = now()
      FROM purchase_receive_records r
     WHERE r.id = pri.receive_record_id
       AND r.factory_id = 'LIUSHANMEN'
       AND pri.material_type_id = v_material_id
       AND lower(btrim(pri.unit)) = 'jin'
       AND pri.inventory_base_unit_snapshot = 'kg'
       AND pri.package_to_base_factor_snapshot = 1;
    GET DIAGNOSTICS v_pri = ROW_COUNT;

    RAISE NOTICE 'V20261029_66: 假包装规格软删 % 条 / 采购单行单位归位 % 条 / 收货行单位归位 % 条',
                 v_spec, v_poi, v_pri;
END $$;

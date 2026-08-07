-- =============================================================================
-- 回滚 V20261029_66 —— 把六膳门的假换算与「斤」标签原样放回去
--
-- 手动执行, Flyway 不跑本目录。
--
-- ⚠️ 回滚会把那条**物理上错误**的 `1斤=1kg` 包装规格恢复成生效状态。
--    只有在确认订正本身出了问题时才用。
-- =============================================================================

DO $$
DECLARE
    v_spec integer := 0;
    v_poi  integer := 0;
    v_pri  integer := 0;
BEGIN
    IF to_regclass('public.migration_jin_unit_fix_20261029_66') IS NULL THEN
        RAISE NOTICE '台账表不存在, V20261029_66 未执行过, 无需回滚';
        RETURN;
    END IF;

    -- ① 恢复被软删的假包装规格
    UPDATE material_packaging_specs s
       SET deleted_at = NULL, updated_at = now()
      FROM migration_jin_unit_fix_20261029_66 l
     WHERE l.entity = 'packaging_spec'
       AND l.entity_id = s.id
       AND s.deleted_at IS NOT NULL;
    GET DIAGNOSTICS v_spec = ROW_COUNT;

    -- ② 采购单行单位还原 —— 只还原「当前值仍是我们写入的 kg」的行,
    --    迁移后又被人改过的不动(免得覆盖他人编辑)
    UPDATE purchase_order_items poi
       SET unit = l.old_value, updated_at = now()
      FROM migration_jin_unit_fix_20261029_66 l
     WHERE l.entity = 'purchase_order_item'
       AND l.entity_id = poi.id::text
       AND poi.unit = l.new_value;
    GET DIAGNOSTICS v_poi = ROW_COUNT;

    -- ③ 收货行单位还原 (old_value 形如 'jin/jin')
    UPDATE purchase_receive_items pri
       SET unit = split_part(l.old_value, '/', 1),
           price_unit = NULLIF(split_part(l.old_value, '/', 2), ''),
           updated_at = now()
      FROM migration_jin_unit_fix_20261029_66 l
     WHERE l.entity = 'purchase_receive_item'
       AND l.entity_id = pri.id::text
       AND pri.unit = 'kg';
    GET DIAGNOSTICS v_pri = ROW_COUNT;

    RAISE NOTICE 'V20261029_66 回滚: 包装规格 % 条 / 采购单行 % 条 / 收货行 % 条', v_spec, v_poi, v_pri;
END $$;

-- 台账刻意保留; 要彻底清掉再手动:
--   DROP TABLE migration_jin_unit_fix_20261029_66;

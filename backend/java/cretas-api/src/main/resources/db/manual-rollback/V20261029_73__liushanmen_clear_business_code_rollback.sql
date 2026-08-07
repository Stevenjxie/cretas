-- =============================================================================
-- 回滚 V20261029_73 —— 把六膳门的 business_code 放回去
--
-- 手动执行, Flyway 不跑本目录。
-- ⚠️ 只还原「当前 business_code 仍为空」的行 —— 迁移后又被人写过的不动。
-- ⚠️ business_code 是 @Column(updatable=false), 只有 SQL 能写回。
-- =============================================================================

DO $$
DECLARE v_rows integer := 0;
BEGIN
    IF to_regclass('public.migration_lsm_bizcode_clear_20261029_73') IS NULL THEN
        RAISE NOTICE '台账表不存在, V20261029_73 未执行过, 无需回滚';
        RETURN;
    END IF;

    UPDATE raw_material_types m
       SET business_code = l.old_business_code, updated_at = now()
      FROM migration_lsm_bizcode_clear_20261029_73 l
     WHERE m.id = l.material_type_id
       AND m.factory_id = 'LIUSHANMEN'
       AND m.business_code IS NULL;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    RAISE NOTICE 'V20261029_73 回滚: 还原 business_code % 条', v_rows;
END $$;

-- 确认无误后台账可清: DROP TABLE migration_lsm_bizcode_clear_20261029_73;

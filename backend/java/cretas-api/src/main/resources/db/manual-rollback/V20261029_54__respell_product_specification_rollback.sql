-- =============================================================================
-- 回滚 V20261029_54 —— 把规格串还原成迁移前的值
--
-- 手动执行, Flyway 不跑本目录。用法:
--   psql -d cretas_prod_db -f V20261029_54__respell_product_specification_rollback.sql
--
-- 还原源是迁移写下的台账 migration_spec_respell_20261029_54。
-- ⚠️ 只还原「当前值仍等于迁移写入的那个值」的行 —— 迁移之后有人又改过的行不动,
--    否则会把人家后来的编辑一并抹掉。
-- =============================================================================

DO $$
DECLARE
    v_restored integer;
    v_drifted  integer;
BEGIN
    IF to_regclass('public.migration_spec_respell_20261029_54') IS NULL THEN
        RAISE NOTICE '台账表不存在, V20261029_54 未执行过或台账已被清理, 无需回滚';
        RETURN;
    END IF;

    SELECT count(*) INTO v_drifted
      FROM product_types p
      JOIN migration_spec_respell_20261029_54 l ON l.product_type_id = p.id
     WHERE COALESCE(p.specification, '') <> l.new_specification;

    UPDATE product_types p
       SET specification = l.old_specification,
           updated_at    = now()
      FROM migration_spec_respell_20261029_54 l
     WHERE p.id = l.product_type_id
       AND COALESCE(p.specification, '') = l.new_specification;
    GET DIAGNOSTICS v_restored = ROW_COUNT;

    RAISE NOTICE 'V20261029_54 回滚: 还原 % 行', v_restored;
    IF v_drifted > 0 THEN
        RAISE NOTICE 'V20261029_54 回滚: % 行在迁移后又被改过, 未还原(避免覆盖他人编辑)', v_drifted;
    END IF;
END $$;

-- 台账刻意保留 —— 想彻底清掉再手动:
--   DROP TABLE migration_spec_respell_20261029_54;

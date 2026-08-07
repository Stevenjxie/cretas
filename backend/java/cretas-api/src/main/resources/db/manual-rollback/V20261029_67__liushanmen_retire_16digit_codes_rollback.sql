-- =============================================================================
-- 回滚 V20261029_67 —— 把六膳门的 16 位分类码与分段字典放回去
--
-- 手动执行, Flyway 不跑本目录。
--
-- ⚠️ 只还原「当前码仍是我们写入的那个 YLxxx」的行 —— 迁移后又被人改过的不动,
--    免得覆盖他人编辑。
-- 迁移刻意没有动分段字典(见迁移文件头), 所以这里也没有字典的还原逻辑。
-- =============================================================================

DO $$
DECLARE
    v_code integer := 0;
BEGIN
    IF to_regclass('public.migration_liushanmen_code_retire_20261029_67') IS NULL THEN
        RAISE NOTICE '台账表不存在, V20261029_67 未执行过, 无需回滚';
        RETURN;
    END IF;

    UPDATE raw_material_types m
       SET code = l.old_code, updated_at = now()
      FROM migration_liushanmen_code_retire_20261029_67 l
     WHERE m.id = l.material_type_id
       AND m.code = l.new_code;
    GET DIAGNOSTICS v_code = ROW_COUNT;

    RAISE NOTICE 'V20261029_67 回滚: 还原 % 条料号为 16 位分类码', v_code;
END $$;

-- 台账刻意保留; 要彻底清掉再手动:
--   DROP TABLE migration_liushanmen_code_retire_20261029_67;

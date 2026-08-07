-- =============================================================================
-- 回滚 V20261029_69 —— 把六膳门被停用的分段字典放回活跃状态
--
-- 手动执行, Flyway 不跑本目录。
--
-- ⚠️ 只还原「当前仍是软删状态」的那些行 —— 迁移之后又被人手工处理过的不动,
--    免得覆盖他人编辑。
-- 只按台账里记下的 id 还原, 不会顺手把六膳门此前就软删的 15 个 L2 / 226 个 L3 复活。
-- =============================================================================

DO $$
DECLARE
    v_rows integer := 0;
BEGIN
    IF to_regclass('public.migration_liushanmen_segment_retire_20261029_69') IS NULL THEN
        RAISE NOTICE '台账表不存在, V20261029_69 未执行过, 无需回滚';
        RETURN;
    END IF;

    UPDATE material_code_segments s
       SET deleted_at = NULL, updated_at = now()
      FROM migration_liushanmen_segment_retire_20261029_69 l
     WHERE s.id = l.segment_id
       AND s.factory_id = 'LIUSHANMEN'
       AND s.deleted_at IS NOT NULL;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    RAISE NOTICE 'V20261029_69 回滚: 恢复 % 条分段字典, 六膳门建档路径切回 16 位分类码', v_rows;
END $$;

-- 台账刻意保留; 要彻底清掉再手动:
--   DROP TABLE migration_liushanmen_segment_retire_20261029_69;

-- =============================================================================
-- 回滚 V20261029_72 —— F006 换回 16 位分类码 + 恢复 business_code + 恢复分段字典
--
-- 手动执行, Flyway 不跑本目录。
--
-- ⚠️ 只还原「当前值仍是我们写入的那个」的行 —— 迁移后又被人改过的不动, 免得覆盖他人编辑。
-- =============================================================================

DO $$
DECLARE
    v_code    integer := 0;
    v_segment integer := 0;
BEGIN
    IF to_regclass('public.migration_f006_recode_20261029_72') IS NULL THEN
        RAISE NOTICE '台账表不存在, V20261029_72 未执行过, 无需回滚';
        RETURN;
    END IF;

    -- ① 换回 16 位码 + 恢复 business_code
    --    business_code 是 @Column(updatable=false), 只有 SQL 能写回
    UPDATE raw_material_types m
       SET code = l.old_code,
           business_code = l.old_business_code,
           updated_at = now()
      FROM migration_f006_recode_20261029_72 l
     WHERE m.id = l.material_type_id
       AND m.code = l.new_code;
    GET DIAGNOSTICS v_code = ROW_COUNT;

    -- ② 恢复分段字典(只复活台账记下的那些, 不碰 F006 此前就已软删的历史分段)
    IF to_regclass('public.migration_f006_segment_retire_20261029_72') IS NOT NULL THEN
        UPDATE material_code_segments s
           SET deleted_at = NULL, updated_at = now()
          FROM migration_f006_segment_retire_20261029_72 l
         WHERE s.id = l.segment_id
           AND s.factory_id = 'F006'
           AND s.deleted_at IS NOT NULL;
        GET DIAGNOSTICS v_segment = ROW_COUNT;
    END IF;

    RAISE NOTICE 'V20261029_72 回滚: 还原料号 % 条 / 分段字典 % 条', v_code, v_segment;
END $$;

-- 确认无误后台账可手动清掉:
--   DROP TABLE migration_f006_recode_20261029_72;
--   DROP TABLE migration_f006_segment_retire_20261029_72;

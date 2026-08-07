-- =============================================================================
-- 回滚 V20261029_71 —— 把 F006 的流水从备份 schema 放回去
--
-- 手动执行, Flyway 不跑本目录。方向与迁移相反: 先父后子, 反复多轮插。
-- 前提: schema `f006_clear_71` 还在(迁移刻意不 DROP 它)。
-- =============================================================================

DO $$
DECLARE
    r          record;
    v_pass     integer;
    v_rows     bigint;
    v_left     bigint;
    v_total    bigint := 0;
    v_leftover text;
BEGIN
    IF to_regnamespace('f006_clear_71') IS NULL THEN
        RAISE NOTICE '备份 schema f006_clear_71 不存在, V20261029_71 未执行过或备份已清理, 无法回滚';
        RETURN;
    END IF;

    CREATE TEMP TABLE _restore_todo ON COMMIT DROP AS
        SELECT table_name::text AS table_name, false AS done
          FROM migration_f006_clear_20261029_71;

    FOR v_pass IN 1..30 LOOP
        v_left := 0;
        FOR r IN SELECT table_name FROM _restore_todo WHERE NOT done LOOP
            BEGIN
                EXECUTE format('INSERT INTO public.%I SELECT * FROM f006_clear_71.%I',
                               r.table_name, r.table_name);
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                v_total := v_total + v_rows;
                UPDATE _restore_todo SET done = true WHERE table_name = r.table_name;
            EXCEPTION WHEN foreign_key_violation THEN
                v_left := v_left + 1;   -- 父表还没放回来, 下一轮再试
            END;
        END LOOP;
        EXIT WHEN v_left = 0;
    END LOOP;

    SELECT string_agg(table_name, ', ') INTO v_leftover FROM _restore_todo WHERE NOT done;
    IF v_leftover IS NOT NULL THEN
        RAISE EXCEPTION 'V20261029_71 回滚失败: 30 轮后仍插不回 —— %', v_leftover;
    END IF;

    RAISE NOTICE 'V20261029_71 回滚完成: 还原 % 张表 / % 行',
        (SELECT count(*) FROM _restore_todo), v_total;
END $$;

-- 确认无误后备份 schema 与台账可手动清掉:
--   DROP SCHEMA f006_clear_71 CASCADE;
--   DROP TABLE migration_f006_clear_20261029_71;

-- =============================================================================
-- 回滚 V20261029_68 —— 把 60 个工厂租户的全部数据从备份 schema 放回去
--
-- 手动执行, Flyway 不跑本目录。整段包在一个 DO 里, 任一步失败全体回滚。
--
-- 前提: schema `tenant_purge_68` 还在(迁移刻意不 DROP 它)。若已被手动 DROP, 无法回滚 ——
--       台账 `migration_tenant_purge_20261029_68` 只记条数, 靠它还原不了行内容。
--
-- 顺序与迁移同解, 方向相反: 先父后子。factories 主表必须最先插, 否则子表全撞外键;
-- 其余表反复多轮插, 撞外键的留到下一轮。
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
    IF to_regnamespace('tenant_purge_68') IS NULL THEN
        RAISE NOTICE '备份 schema tenant_purge_68 不存在, V20261029_68 未执行过或备份已被清理, 无法回滚';
        RETURN;
    END IF;

    -- 1) 先放回主表, 否则子表全部撞外键
    INSERT INTO public.factories SELECT * FROM tenant_purge_68."factories"
        ON CONFLICT (id) DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RAISE NOTICE '还原 factories % 条', v_rows;

    -- 2) 反复多轮插子表
    CREATE TEMP TABLE _restore_todo ON COMMIT DROP AS
        SELECT table_name::text AS table_name, false AS done
          FROM migration_tenant_purge_20261029_68;

    FOR v_pass IN 1..30 LOOP
        v_left := 0;
        FOR r IN SELECT table_name FROM _restore_todo WHERE NOT done LOOP
            BEGIN
                EXECUTE format('INSERT INTO public.%I SELECT * FROM tenant_purge_68.%I',
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
        RAISE EXCEPTION 'V20261029_68 回滚失败: 30 轮后仍插不回 —— %', v_leftover;
    END IF;

    RAISE NOTICE 'V20261029_68 回滚完成: 还原 % 张表 / % 行',
        (SELECT count(*) FROM _restore_todo), v_total;
END $$;

-- 回滚确认无误后, 备份 schema 与台账可手动清掉:
--   DROP SCHEMA tenant_purge_68 CASCADE;
--   DROP TABLE migration_tenant_purge_20261029_68;

-- =============================================================================
-- V20261029_68: 清理工厂域的历史/测试租户 —— 工厂侧只保留 F006 + LIUSHANMEN
--
-- Steve 拍板 2026-08-07: 「工厂这边的只留 f006 + liushanmen」, 物理删除, 带台账和回滚。
--
-- ⛔ 范围只覆盖 type='FACTORY'
--   餐饮租户(type='RESTAURANT', 38 个)与物流租户(type='LOGISTICS', DEMO_LOGISTICS)
--   **一个都不动** —— 餐饮由另一侧负责, MOCK_REST 当天还在登录使用。
--
-- 名单是**显式快照**而不是 `WHERE type='FACTORY' AND id NOT IN (...)` 动态计算:
--   1. 动态口径会把「本迁移评审之后、部署之前新建的工厂」一并吞掉 —— 范围要等于被评审过的那一份
--   2. 动态口径在 dev/test 库上会去删本地有用的数据; 显式 id 在那些库上大多不存在, 自然近似 no-op
--
-- ## 删除对象(60 个, 2026-08-07 prod 实测)
--   48 个 `FOOD_3101_0xx` Canvas 测试厂 / `MEAT_3101_*` / `OTHER_3101_001` / `TEST_0000_001`
--      / `F999`  —— E2E 与画布联调留下的残渣, 各 1-3 个用户、零业务数据
--   `F003` 绿源食品(6 用户 0 数据) / `F004` 鲜味零售(2 用户 0 数据)
--   `F_CLY_DEMO` 川卤源(8 物料 4 批次) / `F_PPT_DEMO` 味道星球(3 物料)
--   `FOOD_3101_048` E2E测试食品厂(17 用户 1 物料 2 批次)
--   ⚠️ 三个还在用的:
--   `DEMO_FACTORY2` 白垩纪AI示范食品厂 —— 37 用户 / 73 批次 / **2026-08-07 当天有人登录**
--   `DEMO_FACTORY`  白垩纪示范食品厂(旧F001) —— 75 用户 / 212 批次 / 7-18 登录
--   `F001`          测试工厂 —— 74 用户 / 125 批次 / 8-04 登录;
--                   `.env.test.example` 的 `TEST_FACTORY_ADMIN_FACTORY_ID=F001` 指的就是它,
--                   删掉之后那份测试凭证模板与依赖 F001 的本地脚本会失效(已当面告知)
--
-- ## 🔴 扫描面必须是外键闭包, 不是「带 factory_id 的表」
--   第一版按 `factory_id` 列扫了 168 张表 / 346,609 行, 干跑时 20 轮删不动, 卡在 7 张表上。
--   真凶是**不带 factory_id 的孙表**: material_batch_adjustments 挡住 material_batches,
--   equipment_maintenance 挡住 factory_equipment, line_schedules 挡住 scheduling_plans……
--   而 material_batches 删不掉又连坐 factory_warehouses / raw_material_types / suppliers,
--   users 再被 raw_material_types.created_by 挡住 —— **传递性阻塞**。
--
--   顺着外键算闭包, 一共 24 张表**没有** factory_id 列却装着这些租户的数据(最深 2 层):
--     batch_equipment_usage / batch_work_sessions / equipment_maintenance /
--     factory_material_requisition_items / factory_stocktake_items /
--     inquiry_quote_supplier_prices / internal_transfers / line_schedules /
--     material_batch_adjustments / price_list_items / process_material_recipe_items /
--     product_work_process_assignees / production_plan_batch_usages / purchase_order_items /
--     purchase_receive_items / return_order_items / sales_delivery_items / sales_order_items /
--     semi_finished_stocktake_items / smart_bi_field_definition / voucher_entries /
--     internal_transfer_items(depth 2) / worker_assignments(depth 2)
--
--   ⛔ 这也说明:「只按 factory_id 删」不但删不完, 就算 FK 允许也会**静默留下孤儿行** ——
--      408 张表带 factory_id 列, 但只有 38 条外键真指向 factories, 且 **0 条 ON DELETE CASCADE**。
--
--   所以这里先把「要删哪些行」算成一张 key 表(从 factory_id 出发, 顺着单列外键做不动点扩散),
--   再照着它备份 + 删除。闭包里 24 张表全部是单列主键; 库里 10 条多列外键的父表都带
--   factory_id, 不落在这 24 张里 —— 单列写法够用(实测确认)。
--
-- ## 回滚
--   删之前把每一行**原样**备份进 schema `tenant_purge_68`(不是只记条数, 那种回滚是假的)。
--   还原脚本: db/manual-rollback/V20261029_68__purge_nonkept_factory_tenants_rollback.sql
--   备份 schema 刻意保留, 确认无碍后再手动 DROP SCHEMA tenant_purge_68 CASCADE;
--   整库 532 MB / 磁盘余 32G, 留着不占地方。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS tenant_purge_68;

CREATE TABLE IF NOT EXISTS migration_tenant_purge_20261029_68 (
    table_name      text PRIMARY KEY,
    pk_column       text NOT NULL,
    rows_backed_up  bigint NOT NULL,
    rows_deleted    bigint NOT NULL,
    migrated_at     timestamp NOT NULL DEFAULT now()
);

DO $$
DECLARE
    -- 被评审过的那 60 个 id 的显式快照
    v_ids text[] := ARRAY[
        'DEMO_FACTORY','DEMO_FACTORY2','F001','F003','F004','F999','F_CLY_DEMO','F_PPT_DEMO',
        'FOOD_3101_001','FOOD_3101_002','FOOD_3101_003','FOOD_3101_004','FOOD_3101_005',
        'FOOD_3101_006','FOOD_3101_007','FOOD_3101_008','FOOD_3101_009','FOOD_3101_010',
        'FOOD_3101_011','FOOD_3101_012','FOOD_3101_013','FOOD_3101_014','FOOD_3101_015',
        'FOOD_3101_016','FOOD_3101_017','FOOD_3101_018','FOOD_3101_019','FOOD_3101_020',
        'FOOD_3101_021','FOOD_3101_022','FOOD_3101_023','FOOD_3101_024','FOOD_3101_025',
        'FOOD_3101_026','FOOD_3101_027','FOOD_3101_028','FOOD_3101_029','FOOD_3101_030',
        'FOOD_3101_031','FOOD_3101_032','FOOD_3101_033','FOOD_3101_034','FOOD_3101_035',
        'FOOD_3101_036','FOOD_3101_037','FOOD_3101_038','FOOD_3101_039','FOOD_3101_040',
        'FOOD_3101_041','FOOD_3101_042','FOOD_3101_043','FOOD_3101_044','FOOD_3101_045',
        'FOOD_3101_046','FOOD_3101_047','FOOD_3101_048',
        'MEAT_3101_001','MEAT_3101_002','OTHER_3101_001','TEST_0000_001'
    ];
    r          record;
    v_pass     integer;
    v_added    bigint;
    v_rows     bigint;
    v_left     bigint;
    v_total    bigint := 0;
    v_leftover text;
    v_kept     integer;
BEGIN
    -- 防呆①: 名单里一个保留租户都不许出现。写错一个 id 就是把真客户删了。
    IF 'F006' = ANY(v_ids) OR 'LIUSHANMEN' = ANY(v_ids) THEN
        RAISE EXCEPTION 'V20261029_68 中止: 删除名单里出现了要保留的租户';
    END IF;

    -- 防呆②: 名单里不许有非 FACTORY 租户(餐饮/物流一个都不能碰)。
    SELECT count(*) INTO v_kept FROM factories WHERE id = ANY(v_ids) AND type <> 'FACTORY';
    IF v_kept > 0 THEN
        RAISE EXCEPTION 'V20261029_68 中止: 名单里有 % 个非 FACTORY 租户', v_kept;
    END IF;

    -- 防呆③: 删完之后工厂域必须**恰好**剩 F006 + LIUSHANMEN。少一个多一个都中止。
    SELECT count(*) INTO v_kept FROM factories WHERE type = 'FACTORY' AND NOT (id = ANY(v_ids));
    IF v_kept <> 2 THEN
        RAISE EXCEPTION
            'V20261029_68 中止: 删除后工厂域会剩 % 个租户(预期 2: F006 + LIUSHANMEN) —— '
            'prod 的工厂集合与本迁移评审时不一致, 需人工重新核名单', v_kept;
    END IF;

    ---------------------------------------------------------------------------
    -- 0) 元数据: 单列主键 / 单列外键(且指向父表主键)
    ---------------------------------------------------------------------------
    CREATE TEMP TABLE _pk ON COMMIT DROP AS
        SELECT pc.conrelid::regclass::text AS table_name, a.attname::text AS pk_column
          FROM pg_constraint pc
          JOIN pg_attribute a ON a.attrelid = pc.conrelid AND a.attnum = pc.conkey[1]
          JOIN pg_class cl ON cl.oid = pc.conrelid
          JOIN pg_namespace ns ON ns.oid = cl.relnamespace AND ns.nspname = 'public'
         WHERE pc.contype = 'p' AND array_length(pc.conkey, 1) = 1;

    CREATE TEMP TABLE _edge ON COMMIT DROP AS
        SELECT con.conrelid::regclass::text  AS child_table,
               ca.attname::text              AS child_column,
               con.confrelid::regclass::text AS parent_table
          FROM pg_constraint con
          JOIN pg_attribute ca ON ca.attrelid = con.conrelid  AND ca.attnum = con.conkey[1]
          JOIN pg_attribute pa ON pa.attrelid = con.confrelid AND pa.attnum = con.confkey[1]
          JOIN _pk ppk ON ppk.table_name = con.confrelid::regclass::text
                      AND ppk.pk_column  = pa.attname::text
         WHERE con.contype = 'f' AND array_length(con.conkey, 1) = 1;

    ---------------------------------------------------------------------------
    -- 1) 算出「要删哪些行」—— 从 factory_id 出发, 顺外键做不动点扩散
    ---------------------------------------------------------------------------
    CREATE TEMP TABLE _keys (table_name text, key text) ON COMMIT DROP;

    FOR r IN
        SELECT c.table_name::text AS table_name, pk.pk_column
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema = c.table_schema AND t.table_name = c.table_name
           AND t.table_type = 'BASE TABLE'
          JOIN _pk pk ON pk.table_name = c.table_name::text
         WHERE c.column_name = 'factory_id' AND c.table_schema = 'public'
    LOOP
        EXECUTE format(
            'INSERT INTO _keys SELECT %L, %I::text FROM public.%I WHERE factory_id = ANY($1)',
            r.table_name, r.pk_column, r.table_name) USING v_ids;
    END LOOP;

    INSERT INTO _keys SELECT 'factories', id FROM public.factories WHERE id = ANY(v_ids);
    CREATE INDEX ON _keys (table_name, key);

    FOR v_pass IN 1..10 LOOP
        v_added := 0;
        FOR r IN
            SELECT e.child_table, e.child_column, e.parent_table, pk.pk_column AS child_pk
              FROM _edge e
              JOIN _pk pk ON pk.table_name = e.child_table
             WHERE EXISTS (SELECT 1 FROM _keys k WHERE k.table_name = e.parent_table)
        LOOP
            EXECUTE format($f$
                INSERT INTO _keys
                SELECT %L, c.%I::text FROM public.%I c
                 WHERE c.%I::text IN (SELECT key FROM _keys WHERE table_name = %L)
                   AND NOT EXISTS (SELECT 1 FROM _keys k
                                    WHERE k.table_name = %L AND k.key = c.%I::text)
            $f$, r.child_table, r.child_pk, r.child_table,
                 r.child_column, r.parent_table,
                 r.child_table, r.child_pk);
            GET DIAGNOSTICS v_rows = ROW_COUNT;
            v_added := v_added + v_rows;
        END LOOP;
        RAISE NOTICE 'V20261029_68 闭包扩散第 % 轮: 新增 % 行', v_pass, v_added;
        EXIT WHEN v_added = 0;
    END LOOP;

    ---------------------------------------------------------------------------
    -- 2) 原样备份(删之前)。只备份真有行的表, 免得留一堆空表。
    ---------------------------------------------------------------------------
    FOR r IN
        SELECT k.table_name, pk.pk_column, count(*) AS n
          FROM _keys k JOIN _pk pk ON pk.table_name = k.table_name
         WHERE k.table_name <> 'factories'
         GROUP BY 1, 2 ORDER BY 1
    LOOP
        EXECUTE format(
            'CREATE TABLE tenant_purge_68.%I AS SELECT * FROM public.%I t '
            'WHERE t.%I::text IN (SELECT key FROM _keys WHERE table_name = %L)',
            r.table_name, r.table_name, r.pk_column, r.table_name);
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        INSERT INTO migration_tenant_purge_20261029_68
                    (table_name, pk_column, rows_backed_up, rows_deleted)
        VALUES (r.table_name, r.pk_column, v_rows, 0)
        ON CONFLICT (table_name) DO UPDATE SET rows_backed_up = EXCLUDED.rows_backed_up;
        v_total := v_total + v_rows;
    END LOOP;

    CREATE TABLE tenant_purge_68."factories" AS
        SELECT * FROM public.factories WHERE id = ANY(v_ids);

    RAISE NOTICE 'V20261029_68 备份完成: % 张表 / % 行',
        (SELECT count(*) FROM migration_tenant_purge_20261029_68), v_total;

    ---------------------------------------------------------------------------
    -- 3) 反复多轮删, 撞外键的留到下一轮。不做拓扑排序 —— 维护一份手写顺序更容易漏。
    ---------------------------------------------------------------------------
    FOR v_pass IN 1..30 LOOP
        v_left := 0;
        FOR r IN SELECT table_name, pk_column FROM migration_tenant_purge_20261029_68
                  WHERE rows_deleted < rows_backed_up
        LOOP
            BEGIN
                EXECUTE format(
                    'DELETE FROM public.%I t WHERE t.%I::text IN '
                    '(SELECT key FROM _keys WHERE table_name = %L)',
                    r.table_name, r.pk_column, r.table_name);
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                UPDATE migration_tenant_purge_20261029_68
                   SET rows_deleted = rows_deleted + v_rows WHERE table_name = r.table_name;
            EXCEPTION WHEN foreign_key_violation THEN
                v_left := v_left + 1;   -- 子表还没删干净, 下一轮再来
            END;
        END LOOP;
        EXIT WHEN v_left = 0;
    END LOOP;

    SELECT string_agg(table_name || '(' || (rows_backed_up - rows_deleted) || ')', ', ')
      INTO v_leftover FROM migration_tenant_purge_20261029_68
     WHERE rows_deleted < rows_backed_up;
    IF v_leftover IS NOT NULL THEN
        RAISE EXCEPTION 'V20261029_68 中止: 30 轮后仍有残留 —— %', v_leftover;
    END IF;

    ---------------------------------------------------------------------------
    -- 4) 最后删主表
    ---------------------------------------------------------------------------
    UPDATE public.factories SET parent_id = NULL
     WHERE parent_id = ANY(v_ids) AND NOT (id = ANY(v_ids));

    DELETE FROM public.factories WHERE id = ANY(v_ids);
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    -- 防呆④: 主表删的条数必须等于名单长度
    IF v_rows <> array_length(v_ids, 1) THEN
        RAISE EXCEPTION 'V20261029_68 中止: factories 实删 % 条, 名单 % 条',
            v_rows, array_length(v_ids, 1);
    END IF;

    -- 防呆⑤: 收尾复核 —— 工厂域必须恰好剩两个
    SELECT count(*) INTO v_kept FROM factories WHERE type = 'FACTORY';
    IF v_kept <> 2 THEN
        RAISE EXCEPTION 'V20261029_68 中止: 收尾复核工厂域剩 % 个(预期 2)', v_kept;
    END IF;

    RAISE NOTICE 'V20261029_68 完成: 删除 % 个工厂租户 / % 张表 / % 行, 备份在 schema tenant_purge_68',
        v_rows, (SELECT count(*) FROM migration_tenant_purge_20261029_68), v_total;
END $$;

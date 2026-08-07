-- =============================================================================
-- V20261029_71: 清空 F006 的批次 / BOM / 采购单（及其外键下游）
--
-- Steve 拍板 2026-08-07:「f006 其实批次 bom 和采购单可以全部清空一下, 反正是用来测试的」。
--
-- ⛔ 只动 F006。LIUSHANMEN(真客户)一行不碰。
--
-- ## 为什么不能只按 factory_id 删
--   与 V20261029_68 同一个坑: `factory_id` 这列在 408 张表上出现, 但只有 38 条外键真指向
--   `factories`, 且 **0 条 ON DELETE CASCADE**。更要命的是**很多子表根本没有 factory_id 列**
--   (material_batch_adjustments / purchase_receive_items / purchase_order_items /
--    bom_item_substitutes / production_plan_batch_usages …),
--   按 factory_id 扫既删不完(传递性外键阻塞), 又会静默留下孤儿行。
--
--   所以沿用 _68 验证过的做法: 先把「要删哪些行」算成一张 key 表(从种子表出发, 顺**单列外键**
--   做不动点扩散), 再照着它备份 + 多轮删除。
--
-- ## 种子(2026-08-07 prod 实测行数)
--   material_batches 323 / purchase_orders 80 / purchase_receive_records 217 /
--   bom_recipes 28 / production_batches 12 / sales_orders 14 / material_consumptions 23
--   闭包会把它们的子表(purchase_order_items 124 / bom_recipe_items 40 …)一并带上。
--
-- ## ⛔ 不动物料档案
--   `raw_material_types` **不在种子里** —— 305 个物料档案保留, 只清业务流水。
--   换码由 V20261029_72 单独做(先清流水再换码, 引用面最小)。
--
-- ## 回滚
--   删前整行备份进 schema `f006_clear_71`(不是只记条数)。
--   还原脚本: db/manual-rollback/V20261029_71__f006_clear_transactional_data_rollback.sql
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS f006_clear_71;

CREATE TABLE IF NOT EXISTS migration_f006_clear_20261029_71 (
    table_name      text PRIMARY KEY,
    pk_column       text   NOT NULL,
    rows_backed_up  bigint NOT NULL,
    rows_deleted    bigint NOT NULL,
    migrated_at     timestamp NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_factory  text := 'F006';
    -- ⚠️ 后三个是补进来的**运行时**表, 不是我主动扩大范围:
    -- 干跑发现 production_batches / work_process_tasks 30 轮删不掉, 被**多列外键**的子表挡住
    -- (production_workflow_instances → production_batches 是 3 列外键,
    --  workflow_task_ports → work_process_tasks 是 3 列),
    -- 而本迁移的闭包扩散只走单列外键(与 _68 同款), 扫不到它们。
    -- 它们是生产批次的运行时产物(工序实例/任务/端口), 随批次一起清是应当的。
    -- ⛔ product_process_workflows(11 条, 工序画布**定义**) 与 production_plans(7 条)
    --    刻意**不清** —— 那是配置不是流水。
    v_seeds    text[] := ARRAY[
        'material_batches', 'purchase_orders', 'purchase_receive_records',
        'bom_recipes', 'production_batches', 'sales_orders', 'material_consumptions',
        'production_workflow_instances', 'work_process_tasks', 'workflow_task_ports'
    ];
    r          record;
    t          text;
    v_pass     integer;
    v_added    bigint;
    v_rows     bigint;
    v_left     bigint;
    v_total    bigint := 0;
    v_leftover text;
    v_check    bigint;
    v_mats_before bigint;
BEGIN
    -- 防呆①: 只允许 F006。写错租户就是删真客户。
    IF v_factory <> 'F006' THEN
        RAISE EXCEPTION 'V20261029_71 中止: 本迁移只针对 F006';
    END IF;

    -- 🔴 环境守卫: 本迁移是**一次性租户清理**, 只对装着那批 prod 数据的库有意义。
    -- 新建的 dev/test 库里没有 F006 的流水, 这里必须**干净跳过**而不是 abort ——
    -- 否则任何一个新环境跑 Flyway 都会中止, 应用直接起不来。
    -- (这一条是 2026-08-07 本地跑之前就该想到、真去本地跑才想起来的。)
    IF NOT EXISTS (SELECT 1 FROM material_batches WHERE factory_id = 'F006') THEN
        RAISE NOTICE 'V20261029_71: 本库没有 F006 的流水, 跳过(非 prod 环境的正常情况)';
        RETURN;
    END IF;

    -- 防呆②: LIUSHANMEN 的行数与 F006 物料档案数必须在迁移前后一致 —— 先记下来
    SELECT count(*) INTO v_check FROM material_batches WHERE factory_id = 'LIUSHANMEN';
    SELECT count(*) INTO v_mats_before FROM raw_material_types WHERE factory_id = 'F006';

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
    -- 1) 种子 + 不动点扩散
    ---------------------------------------------------------------------------
    CREATE TEMP TABLE _keys (table_name text, key text) ON COMMIT DROP;

    FOREACH t IN ARRAY v_seeds LOOP
        EXECUTE format(
            'INSERT INTO _keys SELECT %L, s.%I::text FROM public.%I s WHERE s.factory_id = $1',
            t, (SELECT pk_column FROM _pk WHERE table_name = t), t) USING v_factory;
    END LOOP;
    CREATE INDEX ON _keys (table_name, key);

    FOR v_pass IN 1..12 LOOP
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
        RAISE NOTICE 'V20261029_71 闭包扩散第 % 轮: 新增 % 行', v_pass, v_added;
        EXIT WHEN v_added = 0;
    END LOOP;

    -- 防呆③: 闭包不许碰到物料档案 / 工厂 / 用户 —— 那些不是流水
    IF EXISTS (SELECT 1 FROM _keys WHERE table_name IN
               ('raw_material_types', 'factories', 'users', 'material_code_segments',
                'product_process_workflows', 'production_plans')) THEN
        RAISE EXCEPTION 'V20261029_71 中止: 闭包扩散到了主数据/配置表(物料档案/工厂/用户/工序定义/生产计划), 范围不对';
    END IF;

    -- 防呆④: 闭包里不许出现别的租户的行
    FOR r IN SELECT DISTINCT k.table_name FROM _keys k
              JOIN information_schema.columns c
                ON c.table_schema='public' AND c.table_name=k.table_name AND c.column_name='factory_id'
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM public.%I t WHERE t.%I::text IN (SELECT key FROM _keys WHERE table_name=%L) AND t.factory_id <> $1',
            r.table_name, (SELECT pk_column FROM _pk WHERE table_name = r.table_name),
            r.table_name) INTO v_rows USING v_factory;
        IF v_rows > 0 THEN
            RAISE EXCEPTION 'V20261029_71 中止: % 里有 % 行属于其它租户, 闭包溢出', r.table_name, v_rows;
        END IF;
    END LOOP;

    ---------------------------------------------------------------------------
    -- 2) 原样备份
    ---------------------------------------------------------------------------
    FOR r IN
        SELECT k.table_name, pk.pk_column
          FROM _keys k JOIN _pk pk ON pk.table_name = k.table_name
         GROUP BY 1, 2 ORDER BY 1
    LOOP
        EXECUTE format(
            'CREATE TABLE f006_clear_71.%I AS SELECT * FROM public.%I t '
            'WHERE t.%I::text IN (SELECT key FROM _keys WHERE table_name = %L)',
            r.table_name, r.table_name, r.pk_column, r.table_name);
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        INSERT INTO migration_f006_clear_20261029_71
                    (table_name, pk_column, rows_backed_up, rows_deleted)
        VALUES (r.table_name, r.pk_column, v_rows, 0)
        ON CONFLICT (table_name) DO UPDATE SET rows_backed_up = EXCLUDED.rows_backed_up;
        v_total := v_total + v_rows;
    END LOOP;

    RAISE NOTICE 'V20261029_71 备份完成: % 张表 / % 行',
        (SELECT count(*) FROM migration_f006_clear_20261029_71), v_total;

    ---------------------------------------------------------------------------
    -- 3) 多轮删除, 撞外键的留到下一轮
    ---------------------------------------------------------------------------
    FOR v_pass IN 1..30 LOOP
        v_left := 0;
        FOR r IN SELECT table_name, pk_column FROM migration_f006_clear_20261029_71
                  WHERE rows_deleted < rows_backed_up
        LOOP
            BEGIN
                EXECUTE format(
                    'DELETE FROM public.%I t WHERE t.%I::text IN '
                    '(SELECT key FROM _keys WHERE table_name = %L)',
                    r.table_name, r.pk_column, r.table_name);
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                UPDATE migration_f006_clear_20261029_71
                   SET rows_deleted = rows_deleted + v_rows WHERE table_name = r.table_name;
            EXCEPTION WHEN foreign_key_violation THEN
                v_left := v_left + 1;
            END;
        END LOOP;
        EXIT WHEN v_left = 0;
    END LOOP;

    SELECT string_agg(table_name || '(' || (rows_backed_up - rows_deleted) || ')', ', ')
      INTO v_leftover FROM migration_f006_clear_20261029_71
     WHERE rows_deleted < rows_backed_up;
    IF v_leftover IS NOT NULL THEN
        RAISE EXCEPTION 'V20261029_71 中止: 30 轮后仍有残留 —— %', v_leftover;
    END IF;

    -- 防呆⑤: LIUSHANMEN 一行都不许少
    SELECT count(*) INTO v_rows FROM material_batches WHERE factory_id = 'LIUSHANMEN';
    IF v_rows <> v_check THEN
        RAISE EXCEPTION 'V20261029_71 中止: LIUSHANMEN 批次从 % 变成 %, 误伤真客户', v_check, v_rows;
    END IF;

    -- 防呆⑥: F006 的物料档案必须原样还在
    -- ⚠️ 判据用「与迁移开始时相同」而不是写死 305 —— 写死会让这条迁移只能在 prod 跑
    SELECT count(*) INTO v_rows FROM raw_material_types WHERE factory_id = 'F006';
    IF v_rows <> v_mats_before THEN
        RAISE EXCEPTION 'V20261029_71 中止: F006 物料档案从 % 变成 %, 不该被动',
            v_mats_before, v_rows;
    END IF;

    RAISE NOTICE 'V20261029_71 完成: 清空 F006 流水 % 张表 / % 行, 备份在 schema f006_clear_71',
        (SELECT count(*) FROM migration_f006_clear_20261029_71), v_total;
END $$;

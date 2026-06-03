-- V20260914_03__seed_zhangzhongbao_processes.sql
-- 掌中宝 (椒麻掌中宝) 真实工序链 seed 迁移
-- 产品: 叮咚好食光椒麻掌中宝 120g, product_type_id=1d7fbd73-8797-4933-83f1-46413a45992d
-- 工序链 (5 道): 水解化冻 → 焯水 → 油炸 → 熟制伴汁 → 气调
-- yield 来源: scripts/_liushanmen_dump.txt 掌中宝.xlsx 各 sheet 实际出成率列
-- 幂等: work_processes ON CONFLICT(id) DO NOTHING;
--        product_work_processes WHERE NOT EXISTS (唯一约束 factory+product+work_process)
--        UPDATE 停旧工序幂等

DO $$
BEGIN
    -- Guard: work_processes and product_work_processes are entity-only tables
    -- (Hibernate-managed, not Flyway-created). On fresh-DB the Flyway runner executes
    -- before Hibernate DDL, so these tables may not exist yet. Skip gracefully.
    IF to_regclass('public.product_types') IS NULL
       OR to_regclass('public.work_processes') IS NULL
       OR to_regclass('public.product_work_processes') IS NULL THEN
        RAISE NOTICE 'V20260914_03 seed skipped: entity tables not yet created (fresh-DB Flyway-before-Hibernate)';
        RETURN;
    END IF;

    -- ============================================================
    -- 1. 保证产品类型存在 (prod 已有跳过; test/fresh-DB 缺则建)
    -- ============================================================
    INSERT INTO product_types (id, factory_id, code, name, unit, is_active, created_by, created_at, updated_at)
    VALUES (
        '1d7fbd73-8797-4933-83f1-46413a45992d',
        'F006',
        '6978857198154',
        '叮咚好食光椒麻掌中宝 120g',
        '盒',
        true,
        1,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO NOTHING;

    -- ============================================================
    -- 2. 停用该产品旧通用工序 (在建新工序前执行, 防未来批次 spawn 旧步骤)
    -- ============================================================
    UPDATE product_work_processes
    SET is_active = false,
        updated_at = NOW()
    WHERE factory_id = 'F006'
      AND product_type_id = '1d7fbd73-8797-4933-83f1-46413a45992d'
      AND is_active = true;

    -- ============================================================
    -- 3. 建产品专属 work_processes (5 道)
    -- yield 区间取法: 各 sheet 真实出成率, min 略低于最低值, max 略高于最高值(留超收余量)
    --
    -- 水解化冻: 实测 0.875 / 0.912 / 0.913  → min=0.8600, max=0.9200
    -- 焯水:     实测 0.714 / 0.733 / 0.762  → min=0.7000, max=0.7800
    -- 油炸:     实测 0.779 / 0.798 / 0.802  → min=0.7600, max=0.8300 (脱水工序<1)
    -- 熟制伴汁: 实测 1.267~1.357 (常规), 1.333/1.357 (小批), max 含 1.5 边缘
    --            → min=1.2000, max=1.5500 (加汁增重>1, 保留上限余量)
    -- 气调:     kg→盒 跨单位换算, 出率不设 (NULL)
    -- ============================================================
    INSERT INTO work_processes (
        id,
        factory_id,
        process_name,
        process_category,
        unit,
        output_unit,
        estimated_minutes,
        standard_yield_min,
        standard_yield_max,
        standard_hourly_rate,
        needs_input,
        is_active,
        sort_order
    )
    VALUES
        -- 道1: 水解化冻 (化冻损耗, unit=kg→kg)
        ('WP-F006-ZZB-01', 'F006', '水解化冻', '前处理', 'kg', 'kg',
         NULL, 0.8600, 0.9200, NULL, true, true, 1),

        -- 道2: 焯水 (失水损耗, unit=kg→kg)
        ('WP-F006-ZZB-02', 'F006', '焯水', '加工', 'kg', 'kg',
         NULL, 0.7000, 0.7800, NULL, true, true, 2),

        -- 道3: 油炸 (脱水损耗<1, unit=kg→kg)
        ('WP-F006-ZZB-03', 'F006', '油炸', '加工', 'kg', 'kg',
         NULL, 0.7600, 0.8300, NULL, true, true, 3),

        -- 道4: 熟制伴汁 (加汁增重>1, unit=kg→kg)
        ('WP-F006-ZZB-04', 'F006', '熟制伴汁', '加工', 'kg', 'kg',
         NULL, 1.2000, 1.5500, NULL, true, true, 4),

        -- 道5: 气调(分切装盒) (kg→盒 跨单位, yield=NULL)
        ('WP-F006-ZZB-05', 'F006', '气调', '包装', 'kg', '盒',
         NULL, NULL, NULL, NULL, true, true, 5)

    ON CONFLICT (id) DO NOTHING;

    -- ============================================================
    -- 4. 建 product_work_processes 链 (5 行, is_active=true)
    -- id: BIGINT 自增序列 (nextval), INSERT 省略走 DEFAULT
    -- 幂等: WHERE NOT EXISTS (factory_id + product_type_id + work_process_id 唯一约束)
    -- ============================================================
    INSERT INTO product_work_processes (
        factory_id,
        product_type_id,
        work_process_id,
        process_order,
        is_active,
        created_at,
        updated_at
    )
    SELECT 'F006', '1d7fbd73-8797-4933-83f1-46413a45992d', wp_id, proc_order, true, NOW(), NOW()
    FROM (VALUES
        ('WP-F006-ZZB-01', 1),
        ('WP-F006-ZZB-02', 2),
        ('WP-F006-ZZB-03', 3),
        ('WP-F006-ZZB-04', 4),
        ('WP-F006-ZZB-05', 5)
    ) AS v(wp_id, proc_order)
    WHERE NOT EXISTS (
        SELECT 1
        FROM product_work_processes x
        WHERE x.factory_id    = 'F006'
          AND x.product_type_id = '1d7fbd73-8797-4933-83f1-46413a45992d'
          AND x.work_process_id = v.wp_id
    );

END $$;

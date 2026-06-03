-- V20260914_02__seed_niutui_processes.sql
-- 牛腱 (叮咚好食光纸片牛腱肉 80g) 真实工序链 seed — 5 道
-- 幂等: ON CONFLICT(id) DO NOTHING / WHERE NOT EXISTS / UPDATE 可重跑
-- yield 区间来源: scripts/_liushanmen_dump.txt 纸皮牛肉（牛腱）.xlsx 各 sheet 真实出成率

DO $$
BEGIN
    -- Guard: work_processes and product_work_processes are entity-only tables
    -- (Hibernate-managed, not Flyway-created). On fresh-DB the Flyway runner executes
    -- before Hibernate DDL, so these tables may not exist yet. Skip gracefully.
    IF to_regclass('public.product_types') IS NULL
       OR to_regclass('public.work_processes') IS NULL
       OR to_regclass('public.product_work_processes') IS NULL THEN
        RAISE NOTICE 'V20260914_02 seed skipped: entity tables not yet created (fresh-DB Flyway-before-Hibernate)';
        RETURN;
    END IF;

    -- ============================================================
    -- Step 1: 保证产品类型存在 (prod 已有跳过; fresh-DB / test 缺则建)
    -- ============================================================
    INSERT INTO product_types (id, factory_id, code, name, unit, is_active, created_by, created_at, updated_at)
    VALUES (
        'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c',
        'F006',
        '6978857195160',
        '叮咚好食光纸片牛腱肉 80g',
        '盒',
        true,
        1,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO NOTHING;

    -- ============================================================
    -- Step 2: 停用该产品旧通用工序 (在建新工序前; UPDATE 幂等)
    -- ============================================================
    UPDATE product_work_processes
    SET is_active = false, updated_at = NOW()
    WHERE factory_id = 'F006'
      AND product_type_id = 'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c'
      AND is_active = true;

    -- ============================================================
    -- Step 3: 建产品专属 work_processes (5 道)
    -- 出率来源 (纸皮牛肉（牛腱）.xlsx 真实数据, 留余量):
    --   修油:  R1=0.9754  R2=0.9794  (R3=1.008 异常批次, 仅参考)
    --          实际区间 ~0.975~0.979 → min=0.95  max=1.00
    --          注: 牛腱修油去筋膜/肥油, 出成率较高(~97.5%), R3 可能含多批次汇总
    --   滚揉:  筋少 R1=1.240 R2=1.251 R3=1.251 / 筋多 R1=1.256 R2=1.329 R3=1.323
    --          注射增重(大豆蛋白+料汁), 出成率>1 正常; 区间 1.24~1.33
    --          → min=1.22  max=1.36
    --   焯水:  筋少 R1=0.801 R2=0.856 R3=0.820 / 筋多 R1=0.847 R2=0.887 R3=0.835
    --          综合区间 0.80~0.89 → min=0.78  max=0.90
    --   熟制:  筋少 R1=0.790 R2=0.787 R3=0.820 / 筋多 R1=0.794 R2=0.845 R3=0.803
    --          综合区间 0.78~0.85 → min=0.76  max=0.87
    --   气调:  kg→盒 跨单位, 不设出率 (NULL/NULL)
    -- standard_hourly_rate 全 NULL (Excel 无元/小时数据)
    -- ============================================================
    INSERT INTO work_processes (
        id, factory_id, process_name, process_category,
        unit, output_unit,
        standard_yield_min, standard_yield_max,
        standard_hourly_rate,
        needs_input, is_active, sort_order
    ) VALUES
        ('WP-F006-NT-01', 'F006', '修油',       '前处理', 'kg', 'kg',  0.95, 1.00, NULL, true, true, 1),
        ('WP-F006-NT-02', 'F006', '滚揉(注射保水)', '加工', 'kg', 'kg', 1.22, 1.36, NULL, true, true, 2),
        ('WP-F006-NT-03', 'F006', '焯水',       '加工',   'kg', 'kg',  0.78, 0.90, NULL, true, true, 3),
        ('WP-F006-NT-04', 'F006', '熟制(卤制)', '加工',   'kg', 'kg',  0.76, 0.87, NULL, true, true, 4),
        ('WP-F006-NT-05', 'F006', '气调(抛片装盒)', '包装', 'kg', '盒', NULL, NULL, NULL, true, true, 5)
    ON CONFLICT (id) DO NOTHING;

    -- ============================================================
    -- Step 4: 建 product_work_processes 链 (5 道, is_active=true)
    -- id 由 IDENTITY/serial 自动生成 (GenerationType.IDENTITY)
    -- WHERE NOT EXISTS 防重入幂等 (unique constraint: factory+product+work_process)
    -- ============================================================
    INSERT INTO product_work_processes (
        factory_id, product_type_id, work_process_id,
        process_order, is_active, created_at, updated_at
    )
    SELECT 'F006', 'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c', wp_id, porder, true, NOW(), NOW()
    FROM (VALUES
        ('WP-F006-NT-01', 1),
        ('WP-F006-NT-02', 2),
        ('WP-F006-NT-03', 3),
        ('WP-F006-NT-04', 4),
        ('WP-F006-NT-05', 5)
    ) AS t(wp_id, porder)
    WHERE NOT EXISTS (
        SELECT 1 FROM product_work_processes x
        WHERE x.factory_id      = 'F006'
          AND x.product_type_id = 'c2974690-4ac7-4c17-9ad4-5ee5b12bb26c'
          AND x.work_process_id = t.wp_id
    );

END $$;

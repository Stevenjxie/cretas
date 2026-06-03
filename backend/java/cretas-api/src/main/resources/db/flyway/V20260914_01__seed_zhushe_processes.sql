-- V20260914_01__seed_zhushe_processes.sql
-- 猪舌 (叮咚好食光轻卤门腔) 真实工序链 seed — 6 道
-- 幂等: ON CONFLICT(id) DO NOTHING / WHERE NOT EXISTS / UPDATE 可重跑
-- yield 区间来源: scripts/_liushanmen_dump.txt 猪舌.xlsx 各 sheet 真实出成率

-- ============================================================
-- Step 1: 保证产品类型存在 (prod 已有跳过; fresh-DB / test 缺则建)
-- ============================================================
INSERT INTO product_types (id, factory_id, code, name, unit, is_active, created_by, created_at, updated_at)
VALUES (
    '4e345886-52e4-494a-bcb3-3f0ee9e126b2',
    'F006',
    '6978857198161',
    '叮咚好食光轻卤门腔（猪舌）120g',
    '盒',
    true,
    1,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- Step 2: 停用该产品旧通用工序 (在建新工序前; UPDATE 幂等)
-- 旧工序 product_work_processes id 44-53 全部 is_active=false
-- ============================================================
UPDATE product_work_processes
SET is_active = false, updated_at = NOW()
WHERE factory_id = 'F006'
  AND product_type_id = '4e345886-52e4-494a-bcb3-3f0ee9e126b2'
  AND is_active = true;

-- ============================================================
-- Step 3: 建产品专属 work_processes (6 道)
-- 出率来源 (猪舌.xlsx 真实数据, 4 批次平均 ±余量):
--   修油:    0.892~0.950 → min=0.88  max=0.96
--   滚揉:    1.336~1.387 → min=1.30  max=1.42  (保水 >1 正常)
--   焯水:    0.833~0.919 → min=0.82  max=0.93
--   去舌苔:  0.854~0.880 → min=0.84  max=0.89
--   熟制:    0.806~0.849 → min=0.80  max=0.87  (spec 允许 0.78~0.90)
--   气调:    NULL/NULL   (kg→盒 跨单位, 不设出率)
-- standard_hourly_rate 全 NULL (Excel 无元/小时)
-- ============================================================
INSERT INTO work_processes (
    id, factory_id, process_name, process_category,
    unit, output_unit,
    standard_yield_min, standard_yield_max,
    standard_hourly_rate,
    needs_input, is_active, sort_order
) VALUES
    ('WP-F006-ZS-01', 'F006', '修油',       '前处理', 'kg', 'kg',  0.88, 0.96, NULL, true, true, 1),
    ('WP-F006-ZS-02', 'F006', '滚揉(保水)', '加工',   'kg', 'kg',  1.30, 1.42, NULL, true, true, 2),
    ('WP-F006-ZS-03', 'F006', '焯水',       '加工',   'kg', 'kg',  0.82, 0.93, NULL, true, true, 3),
    ('WP-F006-ZS-04', 'F006', '去舌苔',     '加工',   'kg', 'kg',  0.84, 0.89, NULL, true, true, 4),
    ('WP-F006-ZS-05', 'F006', '熟制(卤制)', '加工',   'kg', 'kg',  0.80, 0.87, NULL, true, true, 5),
    ('WP-F006-ZS-06', 'F006', '气调(分切装盒)', '包装', 'kg', '盒', NULL, NULL, NULL, true, true, 6)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- Step 4: 建 product_work_processes 链 (6 道, is_active=true)
-- id 由 IDENTITY/serial 自动生成 (GenerationType.IDENTITY)
-- WHERE NOT EXISTS 防重入幂等 (unique constraint: factory+product+work_process)
-- ============================================================
INSERT INTO product_work_processes (
    factory_id, product_type_id, work_process_id,
    process_order, is_active, created_at, updated_at
)
SELECT 'F006', '4e345886-52e4-494a-bcb3-3f0ee9e126b2', wp_id, porder, true, NOW(), NOW()
FROM (VALUES
    ('WP-F006-ZS-01', 1),
    ('WP-F006-ZS-02', 2),
    ('WP-F006-ZS-03', 3),
    ('WP-F006-ZS-04', 4),
    ('WP-F006-ZS-05', 5),
    ('WP-F006-ZS-06', 6)
) AS t(wp_id, porder)
WHERE NOT EXISTS (
    SELECT 1 FROM product_work_processes x
    WHERE x.factory_id     = 'F006'
      AND x.product_type_id = '4e345886-52e4-494a-bcb3-3f0ee9e126b2'
      AND x.work_process_id = t.wp_id
);

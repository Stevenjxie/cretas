-- V20260915_06: F006 猪舌工序材料配方 Seed 数据
-- Pilot: 叮咚卤猪舌 4 道有调料/包材成本的工序
--
-- 成本反算说明 (来源: 猪舌.xlsx 调料 sheet + 包装 sheet):
--
-- [滚揉 WP-F006-ZS-02]
--   配料 sheet R43: 滚揉 = 1.5049 元/kg投入 (已摊分, 直接读取)
--   含: C18/花雕酒/花椒/香叶/盐/味精/复合调味料346/姜/冰水; 参考批次投入 8.68kg → 总价 13.06元
--
-- [焯水 WP-F006-ZS-03]
--   配料 sheet R46: 原料焯水成本 = 0.0814 元/kg投入 (已摊分)
--   含: 花雕酒/香芹/香菜/水; 参考投入量同批次
--
-- [卤制 WP-F006-ZS-05] — 净调料反算 (剔除人工)
--   配料 sheet R23-R40: 卤制配料合计 = 136.9479元/锅
--   R49: 该配方对应猪舌投入 140kg/锅 (固定投料比)
--   净调料 unit_cost = 136.9479 ÷ 140 = 0.9782 元/kg猪舌投入
--   含: 八角/桂皮/小茴香/香叶/花椒/白芷/大葱/姜/盐/味精/鸡精/白砂糖/复合调味料346/花雕王/香料油/水
--   注: R49 "卤制成本 136.9479元" 含纯调料, 不含人工 (人工在人工sheet单独核算);
--       R55 "卤制成本 0.1414元/盒" 是人工+调料摊到盒 — 本表只取纯调料部分。
--
-- [气调包材 WP-F006-ZS-06]
--   包装 sheet R10-R13: 气调盒0.23 + 膜0.085 + 气体0.21 + 标签0.0264 = 0.5514 元/盒产出
--   R15 汇总值: 0.5514

-- ============================================================
-- 1. 滚揉调料 WP-F006-ZS-02
-- ============================================================
WITH r1 AS (
    INSERT INTO process_material_recipes
        (factory_id, work_process_id, recipe_type, unit_cost, cost_unit, is_active, remark)
    VALUES
        ('F006', 'WP-F006-ZS-02', 'SEASONING', 1.5049, '元/kg投入', TRUE,
         '猪舌滚揉调料配方; 来源: 猪舌.xlsx调料sheet R43; 反算基准: 批次8.68kg投入, 总调料13.06元')
    RETURNING id
)
INSERT INTO process_material_recipe_items
    (recipe_id, material_name, unit_price, quantity, quantity_unit, line_cost, remark)
SELECT r1.id, v.name, v.up, v.qty, v.qu, v.lc, v.rem
FROM r1, (VALUES
    ('C18',         35.00,   330.0, 'g',   10.2212, '腌制促渗剂'),
    ('花雕酒',        3.50,   120.0, 'g',    0.3717, NULL),
    ('花椒',         72.00,    7.0, 'g',    0.4460, NULL),
    ('香叶',         40.00,    4.0, 'g',    0.1416, NULL),
    ('盐',           0.85,  160.0, 'g',    0.1204, NULL),
    ('味精',         21.67,   20.0, 'g',    0.3835, NULL),
    ('复合调味料346', 125.00,  10.0, 'g',    1.1062, NULL),
    ('姜',           5.58,   55.0, 'g',    0.2716, NULL),
    ('冰水',         NULL,  2000.0, 'g',    NULL,   '成本忽略不计')
) v(name, up, qty, qu, lc, rem);

-- ============================================================
-- 2. 焯水调料 WP-F006-ZS-03
-- ============================================================
WITH r2 AS (
    INSERT INTO process_material_recipes
        (factory_id, work_process_id, recipe_type, unit_cost, cost_unit, is_active, remark)
    VALUES
        ('F006', 'WP-F006-ZS-03', 'SEASONING', 0.0814, '元/kg投入', TRUE,
         '猪舌焯水调料配方; 来源: 猪舌.xlsx调料sheet R46; 原料焯水成本0.0814元/kg')
    RETURNING id
)
INSERT INTO process_material_recipe_items
    (recipe_id, material_name, unit_price, quantity, quantity_unit, line_cost, remark)
SELECT r2.id, v.name, v.up, v.qty, v.qu, v.lc, v.rem
FROM r2, (VALUES
    ('花雕酒',  3.50,    3.5, 'g',   0.0108, NULL),
    ('香芹',  1920.00,   4.6, 'g',   8.8320, '元/kg单价按dump'),
    ('香菜',   320.00,   8.0, 'g',   2.5600, NULL),
    ('水',       NULL, NULL,  NULL,  NULL,   '成本忽略不计')
) v(name, up, qty, qu, lc, rem);

-- ============================================================
-- 3. 卤制净调料 WP-F006-ZS-05  (反算剔人工)
-- ============================================================
WITH r3 AS (
    INSERT INTO process_material_recipes
        (factory_id, work_process_id, recipe_type, unit_cost, cost_unit, is_active, remark)
    VALUES
        ('F006', 'WP-F006-ZS-05', 'SEASONING', 0.9782, '元/kg投入', TRUE,
         '猪舌卤制净调料; 来源: 调料sheet R23-R40合计136.9479元÷140kg猪舌=0.9782元/kg; 已剔除人工(人工在人工sheet独立核算)')
    RETURNING id
)
INSERT INTO process_material_recipe_items
    (recipe_id, material_name, unit_price, quantity, quantity_unit, line_cost, remark)
SELECT r3.id, v.name, v.up, v.qty, v.qu, v.lc, v.rem
FROM r3, (VALUES
    ('八角',         44.00,  100.0, 'g',   3.8938, NULL),
    ('桂皮',         26.00,  100.0, 'g',   2.3009, NULL),
    ('小茴香',       24.00,   60.0, 'g',   1.2743, NULL),
    ('香叶',         40.00,   28.0, 'g',   0.9912, NULL),
    ('花椒',         72.00,   60.0, 'g',   3.8230, NULL),
    ('白芷',         30.00,  120.0, 'g',   3.1858, NULL),
    ('大葱',          2.80, 1400.0, 'g',   3.9200, NULL),
    ('姜',            5.58, 1400.0, 'g',   7.8120, NULL),
    ('盐',            0.85, 3800.0, 'g',   2.8584, NULL),
    ('味精',         14.50, 1200.0, 'g',  15.3982, NULL),
    ('鸡精',         21.67, 1200.0, 'g',  23.0124, NULL),
    ('白砂糖',        7.30,  600.0, 'g',   3.8761, NULL),
    ('复合调味料346', 125.00,  300.0, 'g',  33.1858, NULL),
    ('花雕王',        3.50, 1000.0, 'g',   3.0973, NULL),
    ('香料油',       16.00, 2000.0, 'g',  28.3186, NULL),
    ('水',           NULL,     NULL, NULL,   NULL,  '成本忽略不计')
) v(name, up, qty, qu, lc, rem);

-- ============================================================
-- 4. 气调包材 WP-F006-ZS-06
-- ============================================================
WITH r4 AS (
    INSERT INTO process_material_recipes
        (factory_id, work_process_id, recipe_type, unit_cost, cost_unit, is_active, remark)
    VALUES
        ('F006', 'WP-F006-ZS-06', 'PACKAGING', 0.5514, '元/盒产出', TRUE,
         '猪舌气调包材; 来源: 猪舌.xlsx包装sheet R10-R13; 气调盒0.23+膜0.085+气体0.21+标签0.0264=0.5514元/盒')
    RETURNING id
)
INSERT INTO process_material_recipe_items
    (recipe_id, material_name, unit_price, quantity, quantity_unit, line_cost, remark)
SELECT r4.id, v.name, v.up, v.qty, v.qu, v.lc, v.rem
FROM r4, (VALUES
    ('气调盒(2014-35)', 0.2300, 1, '个', 0.2300, '规格2014-35'),
    ('气调膜',          0.0850, 1, '盒', 0.0850, '4550盒/卷, 26元/kg折算'),
    ('气体',            0.2100, 1, '盒', 0.2100, '0.21元/盒'),
    ('标签',            0.0264, 1, '张', 0.0264, '0.0264元/张')
) v(name, up, qty, qu, lc, rem);

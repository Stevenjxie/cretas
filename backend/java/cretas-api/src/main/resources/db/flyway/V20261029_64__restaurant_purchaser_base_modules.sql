-- V20261029_64: 补齐 restaurant_purchaser 的基础模块行 (16 行)
--
-- 🔴 2026-08-07 prod 实测的缺陷: 六个餐饮角色里, 五个都是 22 行
-- (6 个 restaurant* + 16 个基础模块), 唯独 restaurant_purchaser 只有 6 行 ——
-- **一个基础模块都没有**。
--
--   SELECT role_code, count(*),
--          count(*) FILTER (WHERE module_code NOT LIKE 'restaurant%')
--     FROM platform_role_permissions
--    WHERE role_code IN ('restaurant_manager','sales_manager','finance_manager',
--                        'hr_admin','restaurant_purchaser','restaurant_owner')
--    GROUP BY role_code;
--   → restaurant_purchaser: 6 行 / 非 restaurant 模块 0 个; 其余五个: 22 行 / 16 个
--
-- 根因: V20261029_58 把采购立为第五个部门时只写了那 6 行部门权限, 没给载体角色
-- 其它四个部门载体都有的基础底子。
--
-- 用户可见后果: 采购部门看板 (departmentConfig.ts 的 procurement) 列了三个动作,
-- 采购部长**一个都打不开** ——
--   · 供应商进货录入 /restaurant/supplier-delivery  module='dashboard'   → 无此模块
--   · 报货/采购计划  /procurement/requisitions/my   module='procurement' → 无此模块
--   · 领料/盘点      module='restaurantOps' = '-'                        → 见 _65
--
-- ⛔ 本文件**刻意不带** `L1-AUTHORITY: restaurant-department-matrix` 标记。
-- 那个守卫 (permission.fallback-matches-l1.spec.ts) 用正则抓**任何**
-- ('x','y','z') 三元组并断言恰好 30 行 / 恰好 6 个 restaurant* 模块; 把这 16 行
-- 写进带标记的文件会让它数出 46 行当场红。部门矩阵的重述在 _65。
--
-- 取值口径: 与前端 fallback (`permission.ts` 的 PERMISSION_MATRIX.restaurant_purchaser)
-- 逐格一致 —— 那一侧本来就写着 dashboard/procurement/analytics/finance/warehouse,
-- 是 L1 缺行。两边不一致而守卫是绿的, 因为它只覆盖 6 个 restaurant* 模块,
-- 基础 16 个不在覆盖内 (「闸测的比需要的窄」)。
--
-- inventory / report / work_report 三个模块不在前端 fallback 的类型里, 按最小权限
-- 给 '-' —— 采购不需要它们, 且给 '-' 与「省略」在 weakerOf 下不等价 (省略会跟随
-- `restaurant` 上限 rw), 所以必须显式写出来。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    ('restaurant_purchaser', 'dashboard',   'r'),   -- 供应商进货录入挂在这个模块下
    ('restaurant_purchaser', 'procurement', 'rw'),  -- 他就是采购负责人, 报货要能写
    ('restaurant_purchaser', 'analytics',   'r'),
    ('restaurant_purchaser', 'finance',     'r'),
    ('restaurant_purchaser', 'warehouse',   'r'),
    ('restaurant_purchaser', 'production',  '-'),
    ('restaurant_purchaser', 'quality',     '-'),
    ('restaurant_purchaser', 'sales',       '-'),
    ('restaurant_purchaser', 'hr',          '-'),
    ('restaurant_purchaser', 'equipment',   '-'),
    ('restaurant_purchaser', 'system',      '-'),
    ('restaurant_purchaser', 'scheduling',  '-'),
    ('restaurant_purchaser', 'rd',          '-'),
    ('restaurant_purchaser', 'inventory',   '-'),
    ('restaurant_purchaser', 'report',      '-'),
    ('restaurant_purchaser', 'work_report', '-')
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();

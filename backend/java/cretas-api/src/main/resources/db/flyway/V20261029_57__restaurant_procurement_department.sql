-- V20261029_57: 采购独立成餐饮第五个部门 (restaurantProcurement)
--
-- 决定 (2026-08-06 Steve): 采购不并入市场, 独立成部门。
-- 起因: 「采购并入市场」实施时发现 /procurement/requisitions/my 的 module 是
-- 全局 procurement, 给 sales_manager 开会波及 28 个活跃工厂销售账号(餐饮侧
-- 只有 1 个), 28:1。Steve 据此改为独立成部门。
--
-- 餐饮五个部门: 运营(店长) / 市场 / 财务 / 人事 / 采购
--   restaurant_manager / sales_manager / finance_manager / hr_admin / restaurant_purchaser
-- 厨师长 restaurant_chef 仍然退役, 不在其中(见 FactoryUserRole 的 @Deprecated)。
--
-- ⚠️ 部门键的语义(见 permission.ts): 最终 = min(restaurant 上限, 部门声明值)。
-- **省略部门键 = 跟随上限**, 而店长/市场/财务/人事的上限都是 rw ——
-- 所以必须给它们显式写 restaurantProcurement='-', 否则四个角色白捡一个部门。
-- 前端 fallback 矩阵的九处也同步补过。
--
-- ⚠️ 爆炸半径: 本表是平台全局 L1。工厂型租户由
-- FACTORY_TYPE_MODULE_FILTER.FACTORY={restaurant:'-'} 兜住(上限 '-' 时五个
-- 部门一起关), 与 V20261029_52 同一个机制。
--
-- 幂等: ON CONFLICT DO UPDATE, 与 V20261029_52 写法一致。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    -- 采购部门的载体角色: 上限 rw + 自己的部门 rw + 其余部门关闭
    ('restaurant_purchaser', 'restaurant',            'rw'),
    ('restaurant_purchaser', 'restaurantProcurement', 'rw'),
    ('restaurant_purchaser', 'restaurantOps',         '-'),
    ('restaurant_purchaser', 'restaurantMarketing',   '-'),
    ('restaurant_purchaser', 'restaurantHr',          '-'),
    ('restaurant_purchaser', 'restaurantFinance',     '-'),
    -- 既有四个部门角色: 显式关闭采购部门(不写就会跟随各自 rw 的上限)
    ('restaurant_manager',   'restaurantProcurement', '-'),
    ('sales_manager',        'restaurantProcurement', '-'),
    ('finance_manager',      'restaurantProcurement', '-'),
    ('hr_admin',             'restaurantProcurement', '-'),
    -- 老板全模块只读(V20261029_56 的口径延伸到新部门)
    ('restaurant_owner',     'restaurantProcurement', 'r'),
    -- 平台/工厂总监保持全能
    ('factory_super_admin',  'restaurantProcurement', 'rw'),
    ('platform_admin',       'restaurantProcurement', 'rw'),
    -- 厨师长已退役: 显式关闭, 不留歧义
    ('restaurant_chef',      'restaurantProcurement', '-')
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();

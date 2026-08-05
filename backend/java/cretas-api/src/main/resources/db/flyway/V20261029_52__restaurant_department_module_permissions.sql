-- V20261029_52: 餐饮四部门驾驶舱的分角色细分权限 (L1 平台级)
--
-- 背景: platform_role_permissions 此前只有笼统的 module_code='restaurant',
-- 没有 restaurantOps/Marketing/Hr/Finance 四个细分键。而 permission.ts:552
-- 的规则是:
--     最终 = min(restaurant 上限, 该部门声明值 ?? 上限)
-- 上限是 '-' 时部门声明什么都没用 —— 所以 hr_admin / finance_manager /
-- sales_manager 在餐饮租户里四个部门一个都看不见。
--
-- 本迁移给四个「部门载体角色」各写 5 行(1 上限 + 4 细分), 让一个账号只看得见
-- 自己那一个部门。
--
-- ⚠️ 爆炸半径: 本表是平台全局 L1, 影响所有工厂的这四个角色。靠
-- permission.ts:326 的 FACTORY_TYPE_MODULE_FILTER.FACTORY={restaurant:'-'}
-- 兜住工厂型租户(四个部门随上限一起关)。验收必须实测 F006 的这三个角色
-- 看不见任何餐饮入口。
--
-- ⚠️ 语义改动(有意, 已记入 spec §4.5.5): 把 restaurant_manager 收窄成
-- 「只有运营」等于全局重定义了店长, 与 food_kb/api/manual_chat.py:458
-- 「店长可管理运营、市场、人事并只读财务」相矛盾。当前可接受 —— 租户收敛后
-- 只剩 MOCK_REST 一个活跃餐饮租户。**接入真实餐饮客户前必须重新评估。**
--
-- 刻意不动 restaurant_owner / restaurant_chef: 它们在本表零行(上限 '-',
-- 四部门全不可见), 与 fallback 矩阵矛盾, 属既有问题, 不在本轮范围。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    -- 运营
    ('restaurant_manager', 'restaurant',          'rw'),
    ('restaurant_manager', 'restaurantOps',       'rw'),
    ('restaurant_manager', 'restaurantMarketing', '-'),
    ('restaurant_manager', 'restaurantHr',        '-'),
    ('restaurant_manager', 'restaurantFinance',   '-'),
    -- 市场
    ('sales_manager',      'restaurant',          'rw'),
    ('sales_manager',      'restaurantOps',       '-'),
    ('sales_manager',      'restaurantMarketing', 'rw'),
    ('sales_manager',      'restaurantHr',        '-'),
    ('sales_manager',      'restaurantFinance',   '-'),
    -- 财务
    ('finance_manager',    'restaurant',          'rw'),
    ('finance_manager',    'restaurantOps',       '-'),
    ('finance_manager',    'restaurantMarketing', '-'),
    ('finance_manager',    'restaurantHr',        '-'),
    ('finance_manager',    'restaurantFinance',   'rw'),
    -- 人事
    ('hr_admin',           'restaurant',          'rw'),
    ('hr_admin',           'restaurantOps',       '-'),
    ('hr_admin',           'restaurantMarketing', '-'),
    ('hr_admin',           'restaurantHr',        'rw'),
    ('hr_admin',           'restaurantFinance',   '-')
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();

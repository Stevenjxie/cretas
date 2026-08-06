-- V20261029_61: 餐饮五部门权限矩阵 —— 完整重述 (5 角色 × 6 模块 = 30 行)
--
-- L1-AUTHORITY: restaurant-department-matrix
--
-- ⚠️ 带上面这个标记 = 本文件是 fallback 守卫
-- (web-admin/src/store/modules/permission.fallback-matches-l1.spec.ts) 的权威源。
-- 守卫按「带标记的最高版本迁移」找权威, 所以**改这张表必须完整重述全部 30 行
-- 并保留标记**, 不能只写一条 UPDATE —— 否则守卫会对着旧值比, 而且是绿着比错的。
--
-- 为什么需要本迁移: V20261029_58 把采购加成第五个部门时**没带标记**, 守卫仍指着
-- V20261029_57 那份 4 角色 / 20 行 → 第五个部门与第五个角色落在覆盖之外, 而它是
-- 绿的(实测 grep -c L1-AUTHORITY = 0)。这正是守卫作者预告过的失效方式。
--
-- 30 行取自 2026-08-06 prod 实况直查, 不是手抄快照:
--   SELECT role_code, module_code, permission_level FROM platform_role_permissions
--    WHERE role_code IN (五个载体) AND module_code IN (restaurant + 五个部门);
-- 本迁移与当前 prod 值**逐格一致**, 是幂等重述而非变更。
--
-- 载体角色与部门的对应:
--   restaurant_manager   → restaurantOps        (运营/店长)
--   sales_manager        → restaurantMarketing  (市场)
--   restaurant_purchaser → restaurantProcurement(采购, 2026-08-06 独立成部门)
--   hr_admin             → restaurantHr         (人事)
--   finance_manager      → restaurantFinance    (财务)
-- restaurant 是板块准入上限(不是权限档次), 五个载体都要 rw ——
-- 写 'r' 会经 weakerOf 把该角色**自己部门**也压成只读。
--
-- 唯一的非对角非 '-' 格: restaurant_manager.restaurantHr = 'r' ——
-- 预测排班是人事唯一的功能页, 但店长要看得到(V20261029_57 的决定)。
--
-- ⛔ restaurant_chef 已退役, restaurant_owner 是全模块只读(V20261029_56),
-- 两者都不是部门载体, 故不在这 30 行里。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    -- 运营 (店长)
    ('restaurant_manager',   'restaurant',            'rw'),
    ('restaurant_manager',   'restaurantOps',         'rw'),
    ('restaurant_manager',   'restaurantMarketing',   '-'),
    ('restaurant_manager',   'restaurantHr',          'r'),
    ('restaurant_manager',   'restaurantFinance',     '-'),
    ('restaurant_manager',   'restaurantProcurement', '-'),
    -- 市场
    ('sales_manager',        'restaurant',            'rw'),
    ('sales_manager',        'restaurantOps',         '-'),
    ('sales_manager',        'restaurantMarketing',   'rw'),
    ('sales_manager',        'restaurantHr',          '-'),
    ('sales_manager',        'restaurantFinance',     '-'),
    ('sales_manager',        'restaurantProcurement', '-'),
    -- 采购
    ('restaurant_purchaser', 'restaurant',            'rw'),
    ('restaurant_purchaser', 'restaurantOps',         '-'),
    ('restaurant_purchaser', 'restaurantMarketing',   '-'),
    ('restaurant_purchaser', 'restaurantHr',          '-'),
    ('restaurant_purchaser', 'restaurantFinance',     '-'),
    ('restaurant_purchaser', 'restaurantProcurement', 'rw'),
    -- 人事
    ('hr_admin',             'restaurant',            'rw'),
    ('hr_admin',             'restaurantOps',         '-'),
    ('hr_admin',             'restaurantMarketing',   '-'),
    ('hr_admin',             'restaurantHr',          'rw'),
    ('hr_admin',             'restaurantFinance',     '-'),
    ('hr_admin',             'restaurantProcurement', '-'),
    -- 财务
    ('finance_manager',      'restaurant',            'rw'),
    ('finance_manager',      'restaurantOps',         '-'),
    ('finance_manager',      'restaurantMarketing',   '-'),
    ('finance_manager',      'restaurantHr',          '-'),
    ('finance_manager',      'restaurantFinance',     'rw'),
    ('finance_manager',      'restaurantProcurement', '-')
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();

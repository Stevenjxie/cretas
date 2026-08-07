-- V20261029_65: 餐饮五部门权限矩阵 —— 完整重述 (5 角色 × 6 模块 = 30 行)
--                唯一变化: restaurant_purchaser.restaurantOps  '-' → 'r'
--
-- L1-AUTHORITY: restaurant-department-matrix
--
-- ⚠️ 带上面这个标记 = 本文件是 fallback 守卫
-- (web-admin/src/store/modules/permission.fallback-matches-l1.spec.ts) 的权威源。
-- 守卫按「带标记的最高版本迁移」找权威, 所以**改这张表必须完整重述全部 30 行
-- 并保留标记**, 不能只写一条 UPDATE —— 否则守卫会对着旧值比, 而且是绿着比错的。
--
-- ⛔ 守卫用正则抓**任何** ('x','y','z') 三元组并断言恰好 30 行 / 恰好 6 个
-- restaurant* 模块。所以本文件里**只能有这 30 行**; 采购的 16 个基础模块行在
-- V20261029_64 (不带标记), 写进来会让守卫数出 46 行当场红。
--
-- ── 为什么改这一格 ────────────────────────────────────────────────
-- 权威 `web-admin/src/views/restaurant/departments/departmentConfig.ts` 给采购部门
-- 列的三个动作里, 有两个的 module 是 `restaurantOps`:
--     · 领料管理 /restaurant/requisitions   (注释原话: 领料是采购需求信号)
--     · 盘点管理 /restaurant/stocktaking    (注释原话: 盘点差异是账实校验)
-- 而 restaurant_purchaser.restaurantOps 是 '-' → 采购部长打不开自己部门的看板动作。
--
-- 给 'r' 不给 'rw': 采购要**看**领料/盘点作为进货依据, 但录入与审批仍属运营。
-- 先例是 V20261029_57 给店长 restaurantHr='r' —— 同一个形状: 跨部门只读,
-- 因为那一侧的数据是本部门决策的输入。
--
-- ⚠️ `restaurant` 仍是 'rw' 而不是 'r': 它是**板块准入上限**不是权限档次,
-- 写 'r' 会经 weakerOf 把该角色**自己部门** (restaurantProcurement) 一起压成只读。
--
-- ⛔ restaurant_chef 已退役, restaurant_owner 是全模块只读→全局 rw (V20261029_62),
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
    -- 采购  ← restaurantOps 由 '-' 改为 'r' (本次唯一变化)
    ('restaurant_purchaser', 'restaurant',            'rw'),
    ('restaurant_purchaser', 'restaurantOps',         'r'),
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

-- V20261029_57: 运营部长(店长)对人事部门开只读
--
-- ⚠️ 本文件让过**两次**号: 原名 _55 → _56 → _57。
--
-- 第一次: 并行分支 PR#2340(restaurant_owner 全模块只读)也把它的迁移编成了
-- V20261029_55。文件名不同 → **git 不报冲突** → 两份都合进 main 且两个 PR 的 CI
-- 全绿, 要到 Flyway 启动才炸("Found more than one migration with version ...")。
--
-- 第二次(更值得记): 两个 session **各自独立**去解第一次那个撞车, 又**各自独立**
-- 选了同一个「下一个空闲号」_56 —— 对方的 PR#2342 先合, 我的 PR#2343 随后合,
-- 于是撞车原样搬到了 _56。我的 PR CI 是绿的, 因为它测的合并基底还不含对方那次改名。
--
-- 逮住第二次的是 FlywayVersionUniquenessTest 自己(随 PR#2343 上的 main), 在发布
-- 构建里变红, **部署前**拦下。
--
-- 让号的一直是本文件, 因为两侧都还没部署到 prod(prod 停在 20261029.54)。
-- 📌 教训: 解版本号撞车时, 光挑「下一个空闲号」不够 —— 并发的人会挑到同一个。
--    要先 fetch 最新 main 再挑, 挑完立刻推, 并依赖这条闸在合入后复核。
--
-- L1-AUTHORITY: restaurant-department-matrix
-- ^ 这行标记不要删。web-admin 的 permission.fallback-matches-l1.spec.ts 靠它找
--   「当前最新的那份完整矩阵」—— 带此标记的最高版本迁移即权威。以后再改这张表,
--   **完整重述 20 行**并保留该标记, 不要只写一条 UPDATE, 否则守卫会对着旧值比。
--
-- 背景: V20261029_52 把四个部门载体角色各收敛成「只有自己那一个部门」, 其中
-- restaurant_manager 的 restaurantHr 是 '-'。但预测排班(/restaurant/staffing)
-- 挂的正是 restaurantHr, 而 menuConfig 与 router 的 roles 白名单里都明写着
-- restaurant_manager —— 也就是说**配置自己跟自己打架**, 生产上店长看不到排班。
--
-- 这条冲突一直被前端 fallback 矩阵掩盖: fallback 给店长 restaurantHr='rw',
-- 而 permission.restaurant-departments.spec.ts 把权限 API 全 mock 成空、断言的
-- 正是 fallback 自己 —— 两边相反且永不变红。2026-08-06 对齐 fallback 时才暴露。
--
-- 拍板(Steve, 2026-08-06): 排班属人事部门(它是人事唯一的功能页, 不挪),
-- 但运营部长要看得到 → restaurantHr 给 'r'(只读)。
-- 已知副作用: 店长同时也看得到「人事」驾驶舱页 /restaurant/hr(同一 module)。
-- 只读, 不能改。
--
-- ⚠️ 爆炸半径: 本表是平台全局 L1, 影响所有工厂的 restaurant_manager。
-- 2026-08-06 实测 prod: restaurant_manager 共 1 个账号且是活跃的(MOCK_REST 的
-- mock_ops), 其余餐饮角色 restaurant_owner/chef/purchaser 各 2 个账号但**全部
-- 非活跃** —— 当前组织模型是「四个部门 + 四个部门各自的部长」, 不再细分。
--
-- 下面是四个部门载体角色的**完整**矩阵(4 角色 × 5 模块 = 20 行)。
-- 与 V20261029_52 相比只有一处不同: restaurant_manager.restaurantHr '-' → 'r'。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    -- 运营部长(店长): 运营可写; 人事只读(看排班); 市场/财务不可见
    ('restaurant_manager', 'restaurant',          'rw'),
    ('restaurant_manager', 'restaurantOps',       'rw'),
    ('restaurant_manager', 'restaurantMarketing', '-'),
    ('restaurant_manager', 'restaurantHr',        'r'),
    ('restaurant_manager', 'restaurantFinance',   '-'),
    -- 市场部长
    ('sales_manager',      'restaurant',          'rw'),
    ('sales_manager',      'restaurantOps',       '-'),
    ('sales_manager',      'restaurantMarketing', 'rw'),
    ('sales_manager',      'restaurantHr',        '-'),
    ('sales_manager',      'restaurantFinance',   '-'),
    -- 财务部长
    ('finance_manager',    'restaurant',          'rw'),
    ('finance_manager',    'restaurantOps',       '-'),
    ('finance_manager',    'restaurantMarketing', '-'),
    ('finance_manager',    'restaurantHr',        '-'),
    ('finance_manager',    'restaurantFinance',   'rw'),
    -- 人事部长
    ('hr_admin',           'restaurant',          'rw'),
    ('hr_admin',           'restaurantOps',       '-'),
    ('hr_admin',           'restaurantMarketing', '-'),
    ('hr_admin',           'restaurantHr',        'rw'),
    ('hr_admin',           'restaurantFinance',   '-')
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();

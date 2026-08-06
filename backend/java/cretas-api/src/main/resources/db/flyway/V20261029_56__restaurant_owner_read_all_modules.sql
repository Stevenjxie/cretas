-- V20261029_56: 餐饮老板 (restaurant_owner) 全模块只读
--
-- ⚠️ 本文件原为 V20261029_55, 部署时被 Flyway pre-flight 闸拦下:
-- 另一个 session 在我工作期间合入了同号的
-- V20261029_55__restaurant_manager_reads_hr.sql。两者语义不冲突(那条改
-- restaurant_manager 的 HR 读权限, 本条只碰 restaurant_owner), 纯版本号撞车,
-- 按闸提示改到下一个空号。硬上会让 Spring Flyway 启动直接崩。
--
-- 背景: V20261029_52 明确写了「刻意不动 restaurant_owner / restaurant_chef:
-- 它们在本表零行(上限 '-', 四部门全不可见), 与 fallback 矩阵矛盾, 属既有问题,
-- 不在本轮范围」。本迁移补上 restaurant_owner 那一半。
--
-- 决定 (2026-08-06 Steve): 厨师长 restaurant_chef 退役 (已随租户收敛停用,
-- prod 仅存 2 个账号且均 is_active=f); 餐饮老板 restaurant_owner **保留**,
-- 权限给「所有模块只读」。
--
-- 为什么是只读: 老板是全局视角角色, 需要看得见四个部门的全部经营面, 但执行
-- 动作(改配方/下采购单/发工资/记账)属于各部门本职。给 rw 会让老板成为绕过
-- 部门边界的后门, 而 V20261029_52 刚把部门边界收窄好。
--
-- ⚠️ 爆炸半径: 本表是平台全局 L1, 对所有租户的 restaurant_owner 生效。
-- 实际影响面为零 —— prod 全库 restaurant_owner 账号只有 2 个且都
-- is_active=f。本迁移是把「将来建了老板账号却进不去任何页面」这个坑提前填上。
--
-- ⚠️ 工厂型租户不受影响: permission.ts 的
-- FACTORY_TYPE_MODULE_FILTER.FACTORY={restaurant:'-'} 会把 restaurant* 系列
-- 关掉; 其余模块 restaurant_owner 在工厂租户本就不该出现(该角色 department
-- 归属是 restaurant)。
--
-- 幂等: ON CONFLICT (role_code, module_code) 走 uk_role_module 唯一约束。
-- 已有行不覆盖成更低权限 —— 只在缺行时插入, 避免把人手调过的权限改回去。

-- updated_by 是 bigint(用户 id) 不是字符串, 迁移没有"操作人", 留 NULL。
-- (第一版写成 'V20261029_55' 被 prod 干跑当场打回: invalid input syntax for type bigint)
INSERT INTO platform_role_permissions (role_code, module_code, permission_level, updated_by, updated_at, created_at)
SELECT 'restaurant_owner', m.module_code, 'r', NULL, NOW(), NOW()
  FROM (VALUES
        ('analytics'), ('dashboard'), ('equipment'), ('finance'), ('hr'),
        ('inventory'), ('procurement'), ('production'), ('quality'), ('rd'),
        ('report'), ('restaurant'), ('restaurantFinance'), ('restaurantHr'),
        ('restaurantMarketing'), ('restaurantOps'), ('sales'), ('scheduling'),
        ('system'), ('warehouse'), ('work_report')
       ) AS m(module_code)
ON CONFLICT (role_code, module_code) DO NOTHING;

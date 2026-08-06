-- V20261029_62: 餐饮老板 (restaurant_owner) 全局读写 —— 推翻 V20261029_56 的全只读
--
-- 决定 (2026-08-06 Steve, 原话):「保留吧 作为全局RW，也可以替代其它角色做OA」。
--
-- ⚠️ 本迁移**推翻**同一天的 V20261029_56(全模块 'r')。经过如下:
--   1. 我按「老板权限给所有的 reading」写了 V20261029_56, 全 21 个模块 'r',
--      并在注释里论证「给 rw 会让老板成为绕过部门边界的后门」。
--   2. 对齐前端 fallback 时 8 个 Java 测试变红, 其中一条叫 owner_can_approve_finance。
--      查 PermissionServiceImpl 才发现: 老板的 rw 在后端**不是「能看」而是审批权**
--      (procurement rw=采购审批/请购确认; finance rw=月对账确认/财务审核通过驳回;
--       warehouse rw=报货领料审批/验收入库)。
--   3. 全只读若照字面执行, 老板会失去审批权 —— 上报后 Steve 拍板: 保留, 且要全局 RW,
--      老板可以替代其它角色做 OA。
--
-- 所以 V20261029_56 那句「给 rw 是后门」的论证**作废**: 老板本来就该能替各部门
-- 执行动作, 这是产品口径, 不是漏洞。
--
-- ⚠️ 含 system='rw' —— 那意味着老板也能改权限矩阵/用户。这是「全局 RW」的字面
-- 执行结果, 单独点出来: 若不想给, 单独一条 UPDATE 收回 system 即可, 不影响其余。
--
-- 三处口径本轮一起对齐: 本迁移(L1) + PermissionServiceImpl(Java 强制点)
-- + permission.ts 的 fallback。此前三处各说各话。
--
-- 幂等: ON CONFLICT DO UPDATE。

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
SELECT 'restaurant_owner', m.module_code, 'rw'
  FROM (VALUES
        ('analytics'), ('dashboard'), ('equipment'), ('finance'), ('hr'),
        ('inventory'), ('procurement'), ('production'), ('quality'), ('rd'),
        ('report'), ('restaurant'), ('restaurantFinance'), ('restaurantHr'),
        ('restaurantMarketing'), ('restaurantOps'), ('restaurantProcurement'),
        ('sales'), ('scheduling'), ('system'), ('warehouse'), ('work_report')
       ) AS m(module_code)
ON CONFLICT (role_code, module_code) DO UPDATE
    SET permission_level = EXCLUDED.permission_level,
        updated_at = now();

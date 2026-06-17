-- 修复 (write-only-can't-read bug 类收尾, 续 V20261024_22/23 / #963): 报工角色读不了本职模块
--
-- 背景 (2026-06-17, LIUSHANMEN 工厂实测复现):
--   报工操作员 (yield_operator, baogong001) 逐道报工时 GET .../production/batches/{id}/yield
--   返回 403 "您的角色 [报工操作员] 在 [生产管理] 模块无 [读取] 权限"。整个报工 dialog
--   (产出率/上限 limits/WIP 半成品/报工流水) 全走 production:read → 全 403 → 根本没法报工。
--
-- 根因: 同 #963/#812 write-only bug 类。L1 platform_role_permissions:
--   yield_operator.production = 'w' (write-only); operator.work_report = 'w' (write-only)。
--   checkAction('production:read','w') = "w".contains("read") = false → 所有 GET 403。
--   报工角色本职就是"读任务/上限/WIP + 写报工", 只写不读本职模块是配置 bug。
--
-- 修复: 把这两个 write-only L1 行升到 'rw'。
--   - yield_operator.production 'w'→'rw': 打通逐道报工读取 (yield/limits/wip/reports)。
--   - operator.work_report 'w'→'rw': 操作员可读自己的报工记录 (operator.production 已是 'rw')。
--   (PERMISSION_MATRIX fallback 同步对齐, 见 PermissionServiceImpl, 含 yield_operator.work_report
--    无 L1 行落 matrix 的情形。)
--
-- 范围: platform_role_permissions 为平台全局 L1 → 作用于所有工厂的 operator/yield_operator
--   (全局正确性)。同因排查 (write-only on production/work_report) 至此清零。

UPDATE platform_role_permissions
   SET permission_level = 'rw',
       updated_at = NOW()
 WHERE ((role_code = 'yield_operator' AND module_code = 'production')
     OR (role_code = 'operator'       AND module_code = 'work_report'))
   AND permission_level = 'w'
   AND deleted_at IS NULL;

-- 修复 (same-cause sweep of V20261024_22 / #963): 监督角色质量模块只写不可读
--
-- 背景 (2026-06-17, F006 实测复现 workshop_supervisor):
--   车间主任 (workshop_supervisor) GET /quality-defects 返回 403:
--   "您的角色 [车间主任] 在 [质量管理] 模块无 [读写 / 读取] 权限"。
--
-- 根因: 与 warehouse_worker 扫码 403 同一 bug 类 (Issue #812 模式)。L1
--   platform_role_permissions 中这些线长/主管角色对 quality = 'w' (write-only):
--   checkAction("quality:read", "w") = false, checkAction("quality:read_write", "w") = false
--   → 他们能报质检 (write) 但看不了质检记录/缺陷列表 (read) → 所有 quality GET 端点 403。
--
-- 修复: workshop_supervisor / team_leader / group_leader 的 quality 由 'w'→'rw'。
--   车间主任/大组长/小组长本职就需要查看本线/本组的质检结果与缺陷, 读权限是其职责前提。
--   (quality_inspector 已由 #812 经硬编码 matrix 修复, 因其在 L1 无行; 这三个监督角色
--    在 L1 有实际 'w' 行, 必须改 DB —— 与 #963 warehouse_worker 同理。)
--
-- 范围: platform_role_permissions 为平台全局 L1, 作用于所有工厂的这三个角色 (全局正确性)。
--   仅 workshop_supervisor 在 F006 有账号已实测 403; team_leader/group_leader 为相同
--   write-only 配置与相同语义 (线长读本线质量), 一并修复 (授读不提权, 无风险)。

UPDATE platform_role_permissions
   SET permission_level = 'rw',
       updated_at = NOW()
 WHERE role_code IN ('workshop_supervisor', 'team_leader', 'group_leader')
   AND module_code = 'quality'
   AND permission_level = 'w'
   AND deleted_at IS NULL;

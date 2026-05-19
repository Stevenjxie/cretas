-- ============================================================
-- Sprint 5 Track G — RBAC 数据权限 (data scope) 维度
-- Spec: docs/superpowers/plans/2026-05-19-sprint-5-dispatch.md §G
-- Round 12 §D.1 X4: HJ RBAC 4 维 (功能/数据/打印/第三方); Cretas 仅功能 1 维.
--                    本 migration 补 数据 维 MVP (5 级 scope).
--
-- 兼容性: 所有现有 role 默认 ALL scope, 行为不变.
--         一线员工角色 (operator/warehouse_worker/quality_inspector) 默认 SELF.
-- ============================================================

-- 1. 加 data_scope 列到 role_definitions
ALTER TABLE role_definitions
    ADD COLUMN IF NOT EXISTS data_scope VARCHAR(32) NOT NULL DEFAULT 'ALL';

COMMENT ON COLUMN role_definitions.data_scope IS
    '数据权限范围 (Sprint 5 Track G): ALL / DEPT_AND_BELOW / SELF_AND_BELOW / SELF / CUSTOM';

-- 2. 索引: 通常单 role lookup, 可不加; 但 admin 矩阵列出 scope 时按 scope group by 用
CREATE INDEX IF NOT EXISTS idx_role_definitions_data_scope
    ON role_definitions (data_scope);

-- 3. 一线员工默认 SELF — 仅看自己创建的数据 (per Round 12 §D.1 X4 finding)
UPDATE role_definitions
SET data_scope = 'SELF'
WHERE role_code IN ('operator', 'warehouse_worker', 'quality_inspector')
  AND data_scope = 'ALL';

-- 4. 工厂总监 / 平台管理员 显式 ALL (即默认, 这里幂等保证)
UPDATE role_definitions
SET data_scope = 'ALL'
WHERE role_code IN ('factory_super_admin', 'platform_admin');

-- 5. 验证: 列出当前所有 role + scope, 便于 audit
-- SELECT role_code, display_name, data_scope FROM role_definitions ORDER BY level, role_code;

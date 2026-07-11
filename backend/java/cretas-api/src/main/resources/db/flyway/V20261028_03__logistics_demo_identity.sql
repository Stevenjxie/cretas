-- 一加物流排线 MVP — DEMO_LOGISTICS 租户身份种子 (factories + factory_settings + 演示调度员账号)
-- Spec: docs/superpowers/specs/2026-07-11-logistics-mvp-backend-closed-loop-spec.md
-- Phase 6a: 用真实后端签发 JWT 替换前端硬编码的 fake demo token
-- (web-admin/src/store/modules/auth.ts applyLogisticsDemoUser()).
--
-- 前置: V20261028_02__logistics_demo_seed.sql 已种业务数据 (batches/orders/vehicles/
-- drivers/edges), 全部挂 factory_id='DEMO_LOGISTICS', 但当时未建 factories 行本身 —
-- 若无本迁移, 登录时 user.getFactory() 为 null, factoryType 会静默退化为 'FACTORY'。
--
-- 镜像 V20261024_18__seed_liushanmen_empty_factory.sql 的 factories/factory_settings/
-- users 建号 pattern (该 pattern 是本仓库唯一一处租户身份种子的委托实现参考;
-- DEMO_REST/DEMO_FACTORY2 的种子未提交进本仓库 — 只存在于 prod 数据库, 系历史手工创建)。
--
-- 密码: dispatcher_demo_logistics 的 password_hash 复用 liushanmen_admin 同款
-- onboarding 默认密码 "123456" 的 bcrypt hash (与 V20261024_18/19/20 一致的约定值)。
-- 注意: demoLogin() 走免密登录路径 (MobileAuthServiceImpl.demoLogin), 不校验密码 —
-- 此 password_hash 仅用于满足 NOT NULL 约束 + 防御性支持该账号走 unifiedLogin 手工登录。

INSERT INTO factories (
    id,
    name,
    type,
    level,
    industry,
    is_active,
    manually_verified,
    ai_weekly_quota,
    created_at,
    updated_at
)
VALUES (
    'DEMO_LOGISTICS',
    '一加物流调度中心',
    'LOGISTICS',
    0,
    '物流配送',
    true,
    true,
    30,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    type = EXCLUDED.type,
    level = EXCLUDED.level,
    industry = EXCLUDED.industry,
    is_active = EXCLUDED.is_active,
    manually_verified = EXCLUDED.manually_verified,
    ai_weekly_quota = EXCLUDED.ai_weekly_quota,
    updated_at = NOW();

INSERT INTO factory_settings (
    factory_id,
    factory_name,
    working_hours,
    ai_weekly_quota,
    created_at,
    updated_at
)
SELECT
    'DEMO_LOGISTICS',
    '一加物流调度中心',
    8,
    30,
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM factory_settings WHERE factory_id = 'DEMO_LOGISTICS'
);

UPDATE factory_settings
   SET factory_name = '一加物流调度中心',
       ai_weekly_quota = 30,
       updated_at = NOW()
 WHERE factory_id = 'DEMO_LOGISTICS';

INSERT INTO users (
    factory_id,
    username,
    password_hash,
    full_name,
    department,
    position,
    role_code,
    is_active,
    monthly_salary,
    expected_work_minutes,
    level,
    platform_type,
    created_at,
    updated_at
)
VALUES (
    'DEMO_LOGISTICS',
    'dispatcher_demo_logistics',
    '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse',
    '演示调度员',
    'dispatch',
    'dispatcher',
    'dispatcher',
    true,
    0,
    0,
    10,
    'web,mobile',
    NOW(),
    NOW()
)
ON CONFLICT (username) DO UPDATE
SET factory_id = EXCLUDED.factory_id,
    password_hash = EXCLUDED.password_hash,
    full_name = EXCLUDED.full_name,
    department = EXCLUDED.department,
    position = EXCLUDED.position,
    role_code = EXCLUDED.role_code,
    is_active = true,
    level = 10,
    platform_type = EXCLUDED.platform_type,
    updated_at = NOW();

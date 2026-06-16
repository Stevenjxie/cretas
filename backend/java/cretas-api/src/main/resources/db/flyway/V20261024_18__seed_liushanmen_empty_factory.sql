-- Seed an empty 六膳门 tenant with only one factory super admin account.
-- Password for liushanmen_admin is 123456 (same default onboarding password hash).

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
    'LIUSHANMEN',
    '六膳门',
    'FACTORY',
    0,
    '食品加工',
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
    'LIUSHANMEN',
    '六膳门',
    8,
    30,
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM factory_settings WHERE factory_id = 'LIUSHANMEN'
);

UPDATE factory_settings
   SET factory_name = '六膳门',
       ai_weekly_quota = 30,
       updated_at = NOW()
 WHERE factory_id = 'LIUSHANMEN';

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
    'LIUSHANMEN',
    'liushanmen_admin',
    '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse',
    '六膳门管理员',
    'management',
    'factory_super_admin',
    'factory_super_admin',
    true,
    0,
    0,
    0,
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
    level = 0,
    platform_type = EXCLUDED.platform_type,
    updated_at = NOW();

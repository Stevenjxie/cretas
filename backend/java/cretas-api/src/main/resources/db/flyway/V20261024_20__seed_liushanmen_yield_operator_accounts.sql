-- Seed 六膳门 ordinary report-work accounts.
-- All accounts use password 123456. Usernames are baogong001..baogong010.

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
SELECT
    'LIUSHANMEN',
    v.username,
    '$2b$12$kNRuzD4ZSBttEir6cbwlteBTw7kq2lyz6aQnrwac1sn4i/eTLaRse',
    v.full_name,
    'production',
    'yield_operator',
    'yield_operator',
    true,
    0,
    0,
    30,
    'web,mobile',
    NOW(),
    NOW()
FROM (
    VALUES
        ('baogong001', '六膳门报工001'),
        ('baogong002', '六膳门报工002'),
        ('baogong003', '六膳门报工003'),
        ('baogong004', '六膳门报工004'),
        ('baogong005', '六膳门报工005'),
        ('baogong006', '六膳门报工006'),
        ('baogong007', '六膳门报工007'),
        ('baogong008', '六膳门报工008'),
        ('baogong009', '六膳门报工009'),
        ('baogong010', '六膳门报工010')
) AS v(username, full_name)
ON CONFLICT (username) DO UPDATE
SET factory_id = EXCLUDED.factory_id,
    password_hash = EXCLUDED.password_hash,
    full_name = EXCLUDED.full_name,
    department = EXCLUDED.department,
    position = EXCLUDED.position,
    role_code = EXCLUDED.role_code,
    is_active = true,
    level = EXCLUDED.level,
    platform_type = EXCLUDED.platform_type,
    updated_at = NOW();

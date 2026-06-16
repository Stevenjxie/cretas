-- Seed 六膳门 role test accounts.
-- All accounts use password 123456. Usernames are lsm001..lsm014.

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
    v.department,
    v.role_code,
    v.role_code,
    true,
    0,
    0,
    v.level,
    'web,mobile',
    NOW(),
    NOW()
FROM (
    VALUES
        ('lsm001', '六膳门权限管理员', 'system',      'permission_admin',     10),
        ('lsm002', '六膳门HR管理员',   'hr',          'hr_admin',             10),
        ('lsm003', '六膳门采购主管',   'procurement', 'procurement_manager',  10),
        ('lsm004', '六膳门销售主管',   'sales',       'sales_manager',        10),
        ('lsm005', '六膳门调度',       'dispatch',    'dispatcher',           10),
        ('lsm006', '六膳门仓储主管',   'warehouse',   'warehouse_manager',    10),
        ('lsm007', '六膳门设备管理员', 'equipment',   'equipment_admin',      10),
        ('lsm008', '六膳门质量经理',   'quality',     'quality_manager',      10),
        ('lsm009', '六膳门财务主管',   'finance',     'finance_manager',      10),
        ('lsm010', '六膳门车间主任',   'workshop',    'workshop_supervisor',  20),
        ('lsm011', '六膳门操作员',     'production',  'operator',             30),
        ('lsm012', '六膳门仓库员',     'warehouse',   'warehouse_worker',     30),
        ('lsm013', '六膳门质检员',     'quality',     'quality_inspector',    30),
        ('lsm014', '六膳门查看者',     'none',        'viewer',               50)
) AS v(username, full_name, department, role_code, level)
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

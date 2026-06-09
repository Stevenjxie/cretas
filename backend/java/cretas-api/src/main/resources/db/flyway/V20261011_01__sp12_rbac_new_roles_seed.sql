-- SP12 T1: 新增角色权限种子数据 (cashier 出纳 / quality_controller 品控)
-- cashier: finance:rw + procurement:r
-- quality_controller: quality:rw + production:r + warehouse:r
-- permission_level 使用 platform_role_permissions 规范: 'rw' | 'r' | 'w' | '-'
-- ON CONFLICT DO NOTHING — 幂等，重复跑安全

INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    ('cashier',            'finance',     'rw'),
    ('cashier',            'procurement', 'r'),
    ('quality_controller', 'quality',     'rw'),
    ('quality_controller', 'production',  'r'),
    ('quality_controller', 'warehouse',   'r')
ON CONFLICT (role_code, module_code) DO NOTHING;

-- SP12 T1: 六扇门 (F006) 工厂级 RBAC 覆盖矩阵
-- 严格按 spec §4.4 分配：dispatcher=生产调度, workshop_supervisor=车间主管,
-- yield_operator=报工操作员, warehouse_worker=仓管, quality_inspector=质检,
-- quality_controller=品控, cashier=出纳
--
-- factory_module_permissions 表 (Layer 2: factory-level override)
-- 若表不存在则跳过 (工厂自行在宝塔面板配置)

-- factory_super_admin F006: 全模块 rw (继承 platform, 此处幂等补齐)
INSERT INTO platform_role_permissions (role_code, module_code, permission_level)
VALUES
    -- dispatcher 调度员: 生产+计划+原料查看
    ('dispatcher',        'production',  'rw'),
    ('dispatcher',        'warehouse',   'r'),
    ('dispatcher',        'procurement', 'r'),
    -- workshop_supervisor 车间主管: 生产读写+撤回申请
    ('workshop_supervisor', 'production', 'rw'),
    ('workshop_supervisor', 'quality',    'r'),
    ('workshop_supervisor', 'warehouse',  'r'),
    -- yield_operator 报工操作员: 仅报工写入
    ('yield_operator',    'production',  'w'),
    -- warehouse_worker 仓管: 仓库读写
    ('warehouse_worker',  'warehouse',   'rw'),
    ('warehouse_worker',  'procurement', 'r'),
    -- quality_inspector 质检员: 质检读写
    ('quality_inspector', 'quality',     'rw'),
    ('quality_inspector', 'production',  'r')
ON CONFLICT (role_code, module_code) DO NOTHING;

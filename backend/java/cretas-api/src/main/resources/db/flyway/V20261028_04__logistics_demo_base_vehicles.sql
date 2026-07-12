-- DEMO_LOGISTICS 基础车辆行 (base vehicles 表)
-- V20261028_02 只种了 logistics_vehicle_profiles (物流方数/区域), 未种 base vehicles 行;
-- 而 LogisticsResourceService.listVehicles() 从 base vehicles 表出发 merge profile —
-- base 无 DEMO_LOGISTICS 行 → 车辆列表空 (司机能显示, 车辆不显示)。
-- 补 4 行 base vehicles (id 与 profiles.vehicle_id 对齐 V-01..V-04), 车牌/司机用原 mock 值。
-- 幂等 ON CONFLICT DO NOTHING。capacity 是 kg 载重语义 (方数在 profiles.capacity_cbm)。
INSERT INTO vehicles (id, factory_id, plate_number, driver_name, driver_phone, capacity, current_load, status, vehicle_type, created_at, updated_at)
VALUES
    ('V-01', 'DEMO_LOGISTICS', '苏E·31L8P', '赵明', '', 3200, 0, 'available', '双温车', NOW(), NOW()),
    ('V-02', 'DEMO_LOGISTICS', '苏E·7K92F', '李蓉', '', 3200, 0, 'available', '双温车', NOW(), NOW()),
    ('V-03', 'DEMO_LOGISTICS', '苏U·6Q28A', '王磊', '', 3000, 0, 'available', '双温车', NOW(), NOW()),
    ('V-04', 'DEMO_LOGISTICS', '外协·苏E83Q', '顺达物流 / 陈师傅', '', 3800, 0, 'available', '双温车', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

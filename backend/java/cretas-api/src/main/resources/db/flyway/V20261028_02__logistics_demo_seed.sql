-- 一加物流排线 MVP — DEMO_LOGISTICS 租户演示数据种子
-- Spec: docs/superpowers/specs/2026-07-11-logistics-mvp-backend-closed-loop-spec.md
-- Handoff: docs/dispatch/2026-07-11-logistics-mvp-closed-loop-handoff.md §7
--
-- 来源: web-admin/src/views/scheduling/logistics/{roadSegments.ts, mockData.ts}
-- 全部使用固定 id (非 gen_random_uuid), 每条 INSERT 用 ON CONFLICT (id) DO NOTHING
-- 保证迁移可重复执行 (幂等/可重跑重置).
--
-- 诚实性: mockData 中的门店坐标 (mapAnchor) 是抽象地图像素坐标 (SVG 布局用), 不是真实
-- 经纬度, 因此 delivery_orders.longitude/latitude 留空, location_status='UNRESOLVED'。
-- distance_km 仅取自 roadSegments.ts 的真实维护值, 不做估算/伪造。

-- ============================================================
-- 1. logistics_order_batches — 一条演示批次
-- ============================================================
INSERT INTO logistics_order_batches (
    id, factory_id, business_date, batch_number, source_filename,
    source_fingerprint, status, total_rows, valid_rows, error_rows, created_by
) VALUES (
    'DEMO-BATCH-01', 'DEMO_LOGISTICS', '2026-07-11', 'SCH-20260711-DEMO', 'demo-seed',
    'demo-seed-fixed', 'COMMITTED', 13, 13, 0, 'demo-seed'
)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 2. logistics_delivery_orders — 13 家演示门店 (mockData.ts MOCK_STORES)
-- ============================================================
INSERT INTO logistics_delivery_orders (
    id, factory_id, batch_id, store_code, store_name, address, area_code,
    pieces, boxes, weight_kg, volume_cbm, delivery_window_start, delivery_window_end,
    longitude, latitude, location_status, status, source_row_number
) VALUES
    ('DEMO-ORD-001', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-001', '配送门店 01', '苏州市相城区配送点 01', '相城', 126, 18, 540, 1.8, '09:30', '12:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 1),
    ('DEMO-ORD-002', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-002', '配送门店 02', '苏州市虎丘区配送点 02', '虎丘', 210, 28, 830, 2.7, '10:00', '13:30', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 2),
    ('DEMO-ORD-003', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-003', '配送门店 03', '苏州市姑苏区配送点 03', '姑苏', 158, 22, 690, 2.3, '10:30', '14:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 3),
    ('DEMO-ORD-004', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-004', '配送门店 04', '苏州市姑苏区配送点 04', '姑苏', 120, 16, 480, 1.6, '11:00', '15:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 4),
    ('DEMO-ORD-005', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-005', '配送门店 05', '苏州市吴中区配送点 05', '吴中', 145, 20, 620, 2.1, '13:00', '16:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 5),
    ('DEMO-ORD-006', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-006', '配送门店 06', '苏州市工业园区配送点 06', '园区', 98, 15, 410, 1.4, '13:30', '17:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 6),
    ('DEMO-ORD-007', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-007', '配送门店 07', '苏州市工业园区配送点 07', '园区', 235, 30, 920, 3.2, '14:00', '17:30', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 7),
    ('DEMO-ORD-008', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-008', '配送门店 08', '苏州市吴中区配送点 08', '吴中', 134, 17, 510, 1.7, '15:00', '18:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 8),
    ('DEMO-ORD-009', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-009', '配送门店 09', '苏州市高新区配送点 09', '高新', 176, 24, 720, 2.4, '09:00', '12:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 9),
    ('DEMO-ORD-010', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-010', '配送门店 10', '苏州市高新区配送点 10', '高新', 161, 21, 640, 2.2, '10:00', '13:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 10),
    ('DEMO-ORD-011', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-011', '配送门店 11', '苏州市吴中区配送点 11', '吴中', 86, 13, 360, 1.2, '15:00', '19:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 11),
    ('DEMO-ORD-012', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-012', '配送门店 12', '苏州市吴江区配送点 12', '吴江', 190, 26, 810, 2.8, '16:00', '20:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 12),
    ('DEMO-ORD-013', 'DEMO_LOGISTICS', 'DEMO-BATCH-01', 'S-013', '配送门店 13', '苏州市吴中区配送点 13', '吴中', 168, 22, 700, 2.0, '16:30', '20:00', NULL, NULL, 'UNRESOLVED', 'IMPORTED', 13)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 3. logistics_vehicle_profiles — 4 台演示车辆 (mockData.ts MOCK_VEHICLES)
-- vehicle_id 直接用 mock 车辆 id (V-01..V-04); 无 vehicles 表 FK 约束.
-- source: 自有→OWNED, 外协→OUTSOURCED
-- ============================================================
INSERT INTO logistics_vehicle_profiles (
    id, vehicle_id, factory_id, capacity_cbm, max_weight_kg, source, body_type,
    temperature_mode, service_areas, available_from, available_to, active
) VALUES
    ('DEMO-VP-01', 'V-01', 'DEMO_LOGISTICS', 10, 3200, 'OWNED', '双温车', 'DUAL_TEMP', '姑苏,相城', '08:00', '18:00', TRUE),
    ('DEMO-VP-02', 'V-02', 'DEMO_LOGISTICS', 10, 3200, 'OWNED', '双温车', 'DUAL_TEMP', '园区,吴中', '09:00', '20:00', TRUE),
    ('DEMO-VP-03', 'V-03', 'DEMO_LOGISTICS', 10, 3000, 'OWNED', '双温车', 'DUAL_TEMP', '高新,虎丘', '08:30', '18:30', TRUE),
    ('DEMO-VP-04', 'V-04', 'DEMO_LOGISTICS', 12, 3800, 'OUTSOURCED', '双温车', 'DUAL_TEMP', '吴江,昆山', NULL, NULL, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 4. logistics_drivers — 演示司机 (mock driverName + backupDrivers 去重后共 7 人)
-- employment_type 按其所属车辆的 source 推导 (自有车司机=OWNED, 外协车司机=OUTSOURCED)
-- ============================================================
INSERT INTO logistics_drivers (
    id, factory_id, name, phone, employment_type, service_areas, available_from, available_to, active
) VALUES
    ('DEMO-DRV-01', 'DEMO_LOGISTICS', '司机甲', NULL, 'OWNED', NULL, NULL, NULL, TRUE),
    ('DEMO-DRV-02', 'DEMO_LOGISTICS', '司机乙', NULL, 'OWNED', NULL, NULL, NULL, TRUE),
    ('DEMO-DRV-03', 'DEMO_LOGISTICS', '司机丙', NULL, 'OWNED', NULL, NULL, NULL, TRUE),
    ('DEMO-DRV-04', 'DEMO_LOGISTICS', '司机丁', NULL, 'OWNED', NULL, NULL, NULL, TRUE),
    ('DEMO-DRV-05', 'DEMO_LOGISTICS', '司机戊', NULL, 'OWNED', NULL, NULL, NULL, TRUE),
    ('DEMO-DRV-06', 'DEMO_LOGISTICS', '外协司机', NULL, 'OUTSOURCED', NULL, NULL, NULL, TRUE),
    ('DEMO-DRV-07', 'DEMO_LOGISTICS', '外协调度确认', NULL, 'OUTSOURCED', NULL, NULL, NULL, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 5. logistics_vehicle_drivers — 车辆-司机绑定 (每车 1 主 + 1 备)
-- V-01: 主=司机甲(DRV-01) 08:00-18:00, 备=司机乙(DRV-02)
-- V-02: 主=司机丙(DRV-03) 09:00-20:00, 备=司机丁(DRV-04)
-- V-03: 主=司机戊(DRV-05) 08:30-18:30, 备=司机甲(DRV-01) (同一人跨车备份, 不同 vehicle_id 不冲突唯一索引)
-- V-04: 主=外协司机(DRV-06) 班次='按单确认'→NULL, 备=外协调度确认(DRV-07)
-- ============================================================
INSERT INTO logistics_vehicle_drivers (
    id, factory_id, vehicle_id, driver_id, role, shift_start, shift_end, priority, active
) VALUES
    ('DEMO-VD-01', 'DEMO_LOGISTICS', 'V-01', 'DEMO-DRV-01', 'PRIMARY', '08:00', '18:00', 0, TRUE),
    ('DEMO-VD-02', 'DEMO_LOGISTICS', 'V-01', 'DEMO-DRV-02', 'BACKUP', '08:00', '18:00', 1, TRUE),
    ('DEMO-VD-03', 'DEMO_LOGISTICS', 'V-02', 'DEMO-DRV-03', 'PRIMARY', '09:00', '20:00', 0, TRUE),
    ('DEMO-VD-04', 'DEMO_LOGISTICS', 'V-02', 'DEMO-DRV-04', 'BACKUP', '09:00', '20:00', 1, TRUE),
    ('DEMO-VD-05', 'DEMO_LOGISTICS', 'V-03', 'DEMO-DRV-05', 'PRIMARY', '08:30', '18:30', 0, TRUE),
    ('DEMO-VD-06', 'DEMO_LOGISTICS', 'V-03', 'DEMO-DRV-01', 'BACKUP', '08:30', '18:30', 1, TRUE),
    ('DEMO-VD-07', 'DEMO_LOGISTICS', 'V-04', 'DEMO-DRV-06', 'PRIMARY', NULL, NULL, 0, TRUE),
    ('DEMO-VD-08', 'DEMO_LOGISTICS', 'V-04', 'DEMO-DRV-07', 'BACKUP', NULL, NULL, 1, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 6. logistics_distance_edges — 24 条道路段 (roadSegments.ts ROAD_SEGMENTS)
-- geometry = [起点, ...waypoints, 终点] 完整镜像 roadSegments.ts segment() 组装逻辑
-- distance_km 为 roadSegments.ts 维护的真实值, 不做估算.
-- ============================================================
INSERT INTO logistics_distance_edges (
    id, factory_id, from_point_id, to_point_id, distance_km, geometry, source
) VALUES
    ('DEMO-DE-01', 'DEMO_LOGISTICS', 'DEPOT', 'S-001', 17.6,
        '[{"x":965,"y":600},{"x":830,"y":490},{"x":720,"y":330},{"x":665,"y":210}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-02', 'DEMO_LOGISTICS', 'S-001', 'S-003', 8.4,
        '[{"x":665,"y":210},{"x":760,"y":265},{"x":850,"y":350},{"x":932,"y":441}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-03', 'DEMO_LOGISTICS', 'S-003', 'S-004', 4.2,
        '[{"x":932,"y":441},{"x":970,"y":470},{"x":930,"y":500}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-04', 'DEMO_LOGISTICS', 'DEPOT', 'S-003', 11.8,
        '[{"x":965,"y":600},{"x":910,"y":545},{"x":900,"y":480},{"x":932,"y":441}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-05', 'DEMO_LOGISTICS', 'S-003', 'S-001', 8.8,
        '[{"x":932,"y":441},{"x":860,"y":360},{"x":750,"y":280},{"x":665,"y":210}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-06', 'DEMO_LOGISTICS', 'S-001', 'S-004', 12.1,
        '[{"x":665,"y":210},{"x":760,"y":320},{"x":850,"y":420},{"x":930,"y":500}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-07', 'DEMO_LOGISTICS', 'DEPOT', 'S-005', 24.6,
        '[{"x":965,"y":600},{"x":1100,"y":560},{"x":1300,"y":480},{"x":1490,"y":411}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-08', 'DEMO_LOGISTICS', 'S-005', 'S-006', 7.8,
        '[{"x":1490,"y":411},{"x":1440,"y":450},{"x":1340,"y":490}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-09', 'DEMO_LOGISTICS', 'S-006', 'S-007', 6.3,
        '[{"x":1340,"y":490},{"x":1290,"y":520},{"x":1240,"y":535}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-10', 'DEMO_LOGISTICS', 'S-007', 'S-008', 12.4,
        '[{"x":1240,"y":535},{"x":1140,"y":600},{"x":1080,"y":650},{"x":1000,"y":670}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-11', 'DEMO_LOGISTICS', 'DEPOT', 'S-006', 19.7,
        '[{"x":965,"y":600},{"x":1100,"y":530},{"x":1240,"y":500},{"x":1340,"y":490}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-12', 'DEMO_LOGISTICS', 'S-006', 'S-005', 8.1,
        '[{"x":1340,"y":490},{"x":1400,"y":450},{"x":1490,"y":411}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-13', 'DEMO_LOGISTICS', 'S-005', 'S-007', 13.2,
        '[{"x":1490,"y":411},{"x":1400,"y":480},{"x":1300,"y":520},{"x":1240,"y":535}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-14', 'DEMO_LOGISTICS', 'DEPOT', 'S-011', 26.4,
        '[{"x":965,"y":600},{"x":830,"y":760},{"x":720,"y":930},{"x":650,"y":1040}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-15', 'DEMO_LOGISTICS', 'S-011', 'S-013', 10.6,
        '[{"x":650,"y":1040},{"x":740,"y":1000},{"x":840,"y":970},{"x":930,"y":950}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-16', 'DEMO_LOGISTICS', 'DEPOT', 'S-013', 20.8,
        '[{"x":965,"y":600},{"x":950,"y":720},{"x":930,"y":840},{"x":930,"y":950}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-17', 'DEMO_LOGISTICS', 'S-013', 'S-011', 10.9,
        '[{"x":930,"y":950},{"x":840,"y":970},{"x":740,"y":1000},{"x":650,"y":1040}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-18', 'DEMO_LOGISTICS', 'DEPOT', 'S-002', 12.6,
        '[{"x":965,"y":600},{"x":950,"y":500},{"x":925,"y":390}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-19', 'DEMO_LOGISTICS', 'S-002', 'S-009', 18.9,
        '[{"x":925,"y":390},{"x":800,"y":450},{"x":680,"y":540},{"x":575,"y":575}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-20', 'DEMO_LOGISTICS', 'S-009', 'S-010', 9.7,
        '[{"x":575,"y":575},{"x":650,"y":620},{"x":700,"y":650},{"x":775,"y":650}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-21', 'DEMO_LOGISTICS', 'DEPOT', 'S-009', 18.2,
        '[{"x":965,"y":600},{"x":820,"y":590},{"x":680,"y":580},{"x":575,"y":575}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-22', 'DEMO_LOGISTICS', 'S-009', 'S-002', 19.3,
        '[{"x":575,"y":575},{"x":680,"y":500},{"x":800,"y":430},{"x":925,"y":390}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-23', 'DEMO_LOGISTICS', 'S-002', 'S-010', 14.1,
        '[{"x":925,"y":390},{"x":900,"y":480},{"x":820,"y":580},{"x":775,"y":650}]'::jsonb, 'DEMO_FIXTURE'),
    ('DEMO-DE-24', 'DEMO_LOGISTICS', 'DEPOT', 'S-012', 31.5,
        '[{"x":965,"y":600},{"x":1050,"y":800},{"x":1150,"y":950},{"x":1200,"y":1060}]'::jsonb, 'DEMO_FIXTURE')
ON CONFLICT (id) DO NOTHING;

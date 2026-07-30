-- DEMO_LOGISTICS: 外协车 V-04 服务区加「吴中」(吴江,昆山 → 吴江,昆山,吴中)
-- 背景: demo 原车辆服务区互不重叠, 档2 跨车次优化(区域硬约束)无可行改派 → 演示不出多维度优化。
-- V-04 是外协车(灵活覆盖多区符合实际), 其已服务吴江(南)与吴中(西南)地理相邻。加吴中覆盖后,
-- 档2 优化器把 V-02 的吴中簇(4单)改派给就近的 V-04, V-02 只留园区(紧凑), 总里程 107.97→93.39km(-13.5%)。
-- 既演示多维度跨车次优化, 又修正 V-02「一车跨园区+吴中两非相邻区」的宽跨路线。
-- 幂等 UPDATE。仅 DEMO_LOGISTICS 演示租户。
UPDATE logistics_vehicle_profiles
SET service_areas = '吴江,昆山,吴中', updated_at = NOW()
WHERE factory_id = 'DEMO_LOGISTICS' AND vehicle_id = 'V-04';

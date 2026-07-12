-- DEMO_LOGISTICS: V-02 载重上限 3200→3800 kg (与整条园区+吴中配送线匹配)
-- 背景: V-02 是 15 方双温车但原 mock 只配 3200kg 载重, 与车型不匹配 (15 方冷藏车实际载 3.5-5 吨)。
-- 排线算法把 园区+吴中 两区订单归到 V-02 (唯一服务这两区的车), 该线 6 单合计 11.6 方 / 3520 kg。
-- 3520 > 3200 → 重量硬上限拆箱 → 溢出单在同区无第二辆车 → NEEDS_VEHICLE (demo 出现红色死路车次)。
-- 修正 V-02 载重到 3800kg (与 V-04 外协车一致, 车型合理), 整线一车装下 → 4 车次全绿可确认。
-- 幂等 UPDATE。仅 DEMO_LOGISTICS 演示租户。
UPDATE logistics_vehicle_profiles
SET max_weight_kg = 3800, updated_at = NOW()
WHERE factory_id = 'DEMO_LOGISTICS' AND vehicle_id = 'V-02';

-- base vehicles.capacity 是 kg 载重语义 (方数在 profiles.capacity_cbm), 同步保持一致 (仅显示用, 算法读 profile)。
UPDATE vehicles
SET capacity = 3800, updated_at = NOW()
WHERE factory_id = 'DEMO_LOGISTICS' AND id = 'V-02';

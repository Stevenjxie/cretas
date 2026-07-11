-- =====================================================================
-- 档4 (2026-07-12): 多趟排班 (车回仓补货再出发) — 车次计划时刻持久化
--
-- 背景: 客户全天复杂订单场景 — 一辆车跑完一趟回仓补货再出发, 一天多趟。
-- 排线算法 (LogisticsRoutingAlgorithm 档4) 打破「一车一活跃车次」硬约束:
-- 溢出箱在没有空闲车可派 (原诚实落 NEEDS_VEHICLE) 且时刻可行时, 挂到同一辆
-- 已用车作它的第 2/3/… 趟。每趟推演 出发/回仓 时刻 + 迟回仓标记 + 车内趟序。
--
-- 变更 (全部 additive + 幂等 + nullable, 不加 CHECK — PG CHECK 运行时才暴,
-- H2/CI 照不出, 项目已有 2 次前科):
--   1. logistics_trips.planned_depart_min  — 计划出发时刻 (当日分钟, 480=08:00)。
--   2. logistics_trips.return_to_depot_min — 计划回仓时刻 (末站卸货+回程后)。
--   3. logistics_trips.late_return         — 回仓晚于 min(司机班次结束, 车辆可用截止)。
--   4. logistics_trips.vehicle_trip_seq    — 该车当日第几趟 (1-based, 2+=补货再出发)。
--
-- NULL 语义 (诚实降级): 仅该车全部车次坐标齐全时算法才推演时刻; 缺坐标 / 无车
-- / 人工调整后 (时刻已失真) 均为 NULL — 绝不伪造时刻。
-- =====================================================================

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS planned_depart_min INTEGER;

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS return_to_depot_min INTEGER;

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS late_return BOOLEAN;

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS vehicle_trip_seq INTEGER;

COMMENT ON COLUMN logistics_trips.planned_depart_min IS '计划出发时刻(当日分钟, 480=08:00) — 档4多趟排班推演; 缺坐标/人工调整后为NULL(诚实不伪造)';
COMMENT ON COLUMN logistics_trips.return_to_depot_min IS '计划回仓时刻(当日分钟) — 末站卸货+回程行驶后; 多趟链下一趟出发=本值+装货RELOAD时间';
COMMENT ON COLUMN logistics_trips.late_return IS '迟回仓标记 — 计划回仓晚于min(司机班次结束,车辆可用截止); NULL=无时刻/无约束(区分未知与不迟)';
COMMENT ON COLUMN logistics_trips.vehicle_trip_seq IS '该车当日第几趟(1-based) — 2+=回仓补货再出发的后续趟';

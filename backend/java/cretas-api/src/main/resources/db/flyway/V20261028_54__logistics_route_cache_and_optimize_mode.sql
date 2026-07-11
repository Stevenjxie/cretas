-- =====================================================================
-- 档1-B (2026-07-11): 后端接管道路路线计算 + 持久化缓存 + 时间/距离优化模式
--
-- 版本号说明: brief 原定 V20261028_08, 但 V20261028_50..53 (工序链 workflow, Jul 8)
-- 已合入且已 apply 到 prod; flyway out-of-order=false 下低于已 apply 最高版本的
-- 新迁移会被忽略/校验失败 → 顺延为 V20261028_54 (当前全局最高 _53 之后)。
--
-- 变更 (全部 additive + 幂等):
--   1. logistics_trips.road_path — 道路折线缓存 (JSONB 数组, 每点 {"lng":..,"lat":..},
--      GCJ-02)。生成/重生成/人工调整时地图 provider 算一次并持久化; 查看计划直读本列,
--      零地图 API 调用。⚠️ 独立于既有 geometry 列 (那是前端 SVG 兜底地图的 {x,y} 像素点,
--      语义不同不混用)。路线失败/未启用 → NULL (诚实降级)。
--   2. logistics_trips.total_duration_min — 全程预计时长 (分钟)。仅地图 provider
--      路线规划成功才有值; 边距离回落路径无时长数据保持 NULL (诚实, 不伪造)。
--   3. logistics_trips.route_provider — 产出折线的 provider (AMAP/TENCENT/BAIDU),
--      调试用。与折线同生命周期。
--   4. logistics_plans.optimize_by — 排线优化模式 (TIME=时间最快 / DISTANCE=路程最短)。
--      默认 DISTANCE; 旧行回填 DISTANCE 语义由列 DEFAULT + 读侧 null→DISTANCE 双保险,
--      不加 CHECK 约束 (PG CHECK 运行时才暴, H2/CI 照不出 — 项目已有 2 次前科)。
-- =====================================================================

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS road_path JSONB;

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS total_duration_min NUMERIC(10, 2);

ALTER TABLE logistics_trips
    ADD COLUMN IF NOT EXISTS route_provider VARCHAR(16);

ALTER TABLE logistics_plans
    ADD COLUMN IF NOT EXISTS optimize_by VARCHAR(16) DEFAULT 'DISTANCE';

COMMENT ON COLUMN logistics_trips.road_path IS '道路折线缓存(JSONB数组, 每点{"lng":..,"lat":..}, GCJ-02) — 地图provider算一次持久化, 查看零API调用; 独立于geometry(SVG像素点)列; 失败为NULL诚实降级';
COMMENT ON COLUMN logistics_trips.total_duration_min IS '全程预计时长(分钟) — 地图provider路线规划成功才有值, 边距离回落路径为NULL(诚实降级不伪造)';
COMMENT ON COLUMN logistics_trips.route_provider IS '产出road_path折线的地图provider(AMAP/TENCENT/BAIDU), 与折线同生命周期, 排查用';
COMMENT ON COLUMN logistics_plans.optimize_by IS '排线优化模式: TIME=时间最快(高德strategy=0) / DISTANCE=路程最短(strategy=2, 默认); regenerate复用本值';

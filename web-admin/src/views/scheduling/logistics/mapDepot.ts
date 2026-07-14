/**
 * 配送中心真实经纬度 —— 一加物流仓库：苏州相城区望亭海亭路197号沐井供应链。
 * 与后端 `logistics.depot.lng/lat` 配置一致。唯一事实源，供 LogisticsMap / LocationPicker 等
 * 需要「地图默认中心点」的地方共用，禁止散落各处各写一份字面量（2026-07-14 曾因两处不同步埋雷）。
 */
export const DEPOT_LNGLAT: [number, number] = [120.476894, 31.437014];

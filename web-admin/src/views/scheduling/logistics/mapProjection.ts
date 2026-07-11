/**
 * 经纬度 → 静态苏州脱敏底图像素坐标 的集中投影适配器。
 *
 * 背景（handoff §12.4）：后端返回真实经度/纬度（`LogisticsDeliveryOrder.longitude/latitude`），
 * 前端继续使用现有 1917×1165 静态底图（不接真实地图 SDK）。所有需要把经纬度换算成地图像素点的地方
 * 必须走这里，禁止散落各处各写一套换算逻辑。
 *
 * ⚠️ 标定说明：下方 `BOUNDS` 是苏州市区大致经纬度范围的粗略占位标定（未对齐客户提供的底图四角坐标）。
 * 只要backend/客户提供底图四角的真实经纬度锚点，应替换为精确双线性/仿射变换。当前占位实现足以让
 * "有坐标 → 画在地图上、无坐标 → 待定位列表、超出范围 → 标记 OUT_OF_BOUNDS" 这条状态机正确工作，
 * 但像素位置的精确度不代表真实地理位置——不得以此冒充精确路测里程（handoff §7.9 已由后端
 * distance_source 字段管控，前端投影仅用于点位展示）。
 */
import type { MapPoint } from './types';

export const MAP_WIDTH = 1917;
export const MAP_HEIGHT = 1165;

/** 粗略苏州市区经纬度边界（占位标定，见文件头注释） */
const BOUNDS = {
  minLon: 120.30,
  maxLon: 120.85,
  minLat: 31.15,
  maxLat: 31.55,
};

export interface ProjectionResult {
  /** 已 clamp 到地图边界内的像素坐标，可直接用于渲染 */
  point: MapPoint;
  /** 原始经纬度落在标定边界之外（仍返回一个 clamp 到边缘的点，供"仍显示但需提示"场景使用） */
  outOfBounds: boolean;
}

/**
 * 把经纬度投影到地图像素坐标系。
 *
 * 纬度越大越偏北 → 像素 y 越小（地图上方），故 y 轴做了翻转。
 */
export function projectLonLat(longitude: number, latitude: number): ProjectionResult {
  const xRatio = (longitude - BOUNDS.minLon) / (BOUNDS.maxLon - BOUNDS.minLon);
  const yRatio = (BOUNDS.maxLat - latitude) / (BOUNDS.maxLat - BOUNDS.minLat);
  const outOfBounds = !Number.isFinite(xRatio) || !Number.isFinite(yRatio)
    || xRatio < 0 || xRatio > 1 || yRatio < 0 || yRatio > 1;
  const x = Math.min(Math.max(xRatio, 0), 1) * MAP_WIDTH;
  const y = Math.min(Math.max(yRatio, 0), 1) * MAP_HEIGHT;
  return { point: { x, y }, outOfBounds };
}

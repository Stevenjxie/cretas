import type { RouteTrip } from './types';

/** 每站卸货停留（分钟，假设）。 */
export const DWELL_MIN = 10;

export interface StopEta {
  storeId: string;
  /** 预计到达（分钟，自 00:00）。数据不足时为 null。 */
  etaMin: number | null;
  /** 预计到达晚于该门店配送窗口结束。 */
  late: boolean;
}

export interface TimeWindow {
  start: number | null;
  end: number | null;
}

/** "HH:MM" → 分钟；无效返回 null。 */
export function parseHm(hm: string | undefined | null): number | null {
  if (!hm) return null;
  const m = /^(\d{1,2}):(\d{2})/.exec(hm.trim());
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

/** 分钟 → "HH:MM"。 */
export function fmtHm(min: number): string {
  const t = ((Math.round(min) % 1440) + 1440) % 1440;
  return `${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`;
}

/** 门店 window 字符串 "HH:MM-HH:MM" → {start,end}（分钟）。 */
export function parseWindow(window: string | undefined): TimeWindow {
  const [s, e] = (window ?? '').split('-');
  return { start: parseHm(s), end: parseHm(e) };
}

/**
 * 车次每站预计到达 + 迟到判定（纯前端估算，标"预计"，非承诺）。
 * 用车次真实总时长（高德）按各段里程比例分摊 + 每站停留；出发时间取"首站按其窗口开始到达"倒推。
 * 缺时长/里程/窗口 → 不判定（不误报）。与后端时间感知优化器同一到达模型（口径一致）。
 *
 * @param windowOf storeId → 配送时间窗（分钟）
 */
export function tripEtas(trip: RouteTrip, windowOf: (storeId: string) => TimeWindow): StopEta[] {
  const ids = trip.storeIds;
  const segs = trip.segmentDistances ?? [];
  const total = segs.reduce((a, b) => a + (b || 0), 0);
  const dur = trip.durationMin;
  if (!ids.length || !dur || total <= 0 || segs.length < ids.length) {
    return ids.map((storeId): StopEta => ({ storeId, etaMin: null, late: false }));
  }
  const firstStart = windowOf(ids[0]).start;
  const travelTo = (i: number): number => {
    const cum = segs.slice(0, i + 1).reduce((a, b) => a + (b || 0), 0);
    return dur * (cum / total) + i * DWELL_MIN;
  };
  const depart = (firstStart ?? 8 * 60) - travelTo(0);
  return ids.map((storeId, i): StopEta => {
    const eta = depart + travelTo(i);
    const end = windowOf(storeId).end;
    return { storeId, etaMin: eta, late: end != null && eta > end };
  });
}

export function etaLabel(eta: StopEta | undefined): string {
  return eta && eta.etaMin != null ? `预计 ${fmtHm(eta.etaMin)}` : '';
}

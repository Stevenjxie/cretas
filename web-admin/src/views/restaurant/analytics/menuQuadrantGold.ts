/**
 * WS3 Task 1 — pure transform from the WS1 gold `/restaurant-ops/menu-quadrant`
 * payload to the existing `MenuQuadrantData` shape the chart/table/summary code
 * already renders.
 *
 * The gold endpoint returns (after pythonFetch snake→camel):
 *   { items: [{ name, qty, revenue, quadrant(中文) }], qtyMedian, revenueMedian }
 *
 * The legacy CSV path produced `MenuQuadrantData`:
 *   { items: [{ name, quadrant('Star'|'Plow'|'Puzzle'|'Dog'), revenue,
 *               quantity, unitProfit }],
 *     qtyMedian, profitMedian, summary{starCount,...} }
 *
 * Mapping rules:
 *  - quadrant 中文 → English key:  明星→Star, 金牛→Plow, 潜力→Puzzle, 瘦狗→Dog
 *  - qty → quantity
 *  - unitProfit (品均收入) = revenue / qty  (per-item average revenue; the
 *    legacy "收入模式" Y axis). qty=0 → 0 (defensive; gold filters revenue>0 but
 *    qty could theoretically be 0 if data is dirty).
 *  - profitMedian = single-value median of unitProfit (same _median lower-middle
 *    rule the backend uses → stable threshold, one observed value).
 *  - summary = per-quadrant counts.
 *
 * Keeping this a PURE function (no Vue, no fetch) so it's unit-testable without
 * mounting the component, matching the resolveDishesTab.ts test style.
 */
import type { MenuQuadrantData, MenuQuadrantItem } from '@/types/restaurant-analytics'

/** Raw gold item as returned by /restaurant-ops/menu-quadrant (camelCased). */
export interface GoldQuadrantItem {
  name: string
  qty: number
  /** revenue may be null when RBAC strips it for non-price-view roles. */
  revenue: number | null
  /** 中文 quadrant label from the backend: 明星 / 金牛 / 潜力 / 瘦狗. */
  quadrant: string
}

export interface GoldQuadrantPayload {
  items: GoldQuadrantItem[]
  qtyMedian: number
  /** revenueMedian may be null when RBAC strips it. */
  revenueMedian: number | null
}

const QUADRANT_CN_TO_EN: Record<string, MenuQuadrantItem['quadrant']> = {
  明星: 'Star',
  金牛: 'Plow',
  潜力: 'Puzzle',
  瘦狗: 'Dog',
}

/** Lower-middle median (mirrors backend `_median`): sorted[(n-1)//2]; empty → 0. */
function lowerMiddleMedian(values: number[]): number {
  if (values.length === 0) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.floor((sorted.length - 1) / 2)]
}

/**
 * Convert the gold menu-quadrant payload into the legacy `MenuQuadrantData`
 * shape. Returns null when there are no items (honest empty — caller shows
 * empty state rather than an all-zero chart).
 */
export function goldQuadrantToData(
  payload: GoldQuadrantPayload | null | undefined,
): MenuQuadrantData | null {
  if (!payload || !Array.isArray(payload.items) || payload.items.length === 0) {
    return null
  }

  const items: MenuQuadrantItem[] = payload.items.map((it) => {
    const revenue = it.revenue ?? 0
    const quantity = it.qty ?? 0
    const unitProfit = quantity > 0 ? revenue / quantity : 0
    return {
      name: it.name,
      quadrant: QUADRANT_CN_TO_EN[it.quadrant] ?? 'Dog',
      revenue,
      quantity,
      unitProfit,
    }
  })

  const summary = {
    starCount: items.filter((i) => i.quadrant === 'Star').length,
    plowCount: items.filter((i) => i.quadrant === 'Plow').length,
    puzzleCount: items.filter((i) => i.quadrant === 'Puzzle').length,
    dogCount: items.filter((i) => i.quadrant === 'Dog').length,
  }

  return {
    items,
    qtyMedian: payload.qtyMedian ?? 0,
    profitMedian: lowerMiddleMedian(items.map((i) => i.unitProfit)),
    summary,
  }
}

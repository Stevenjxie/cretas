/**
 * Warehouse code → LEGACY hardcoded fallback label. NOT the source of truth.
 *
 * ⚠️ 2026-07-02 fix (LIUSHANMEN customer feedback — "同一仓库在不同页面显示
 * 不同名字, 看起来很乱"): this used to be treated as authoritative and would
 * OVERRIDE the warehouse's real `factory_warehouses.name`. That backfired —
 * LIUSHANMEN renamed WH-LOG's DB name to "外仓" in their own warehouse config,
 * but 分仓库存查询 kept showing "总仓" because this hardcoded map won. A single
 * warehouse ended up wearing 2-3 conflicting names across the UI.
 *
 * DB `name` is now authoritative everywhere (see `warehouseDisplayName` /
 * `warehouseNameByCode` below). This map is ONLY a last-resort fallback for
 * call sites that have nothing but a bare `code` and no loaded warehouse
 * record to resolve a real name from (e.g. before the warehouse list has
 * loaded). Prefer `warehouseDisplayName` (have name+code) or
 * `warehouseNameByCode` (have code + a loaded warehouse list) instead of
 * calling this directly for anything user-facing.
 *
 * D1 UI label policy (PR #346 audit, 2026-05-11): the customer's transcript
 * uses 线边仓 / 总仓 for the two warehouse types. Internal code (DB columns
 * factory_warehouses.code, Java constants) stays WH-WKS / WH-LOG.
 *
 * Glossary: docs/architecture/2026-05-11-warehouse-label-glossary.md
 *
 * Usage (fallback-only):
 *   import { warehouseDisplayLabel } from '@/utils/warehouse';
 *   const label = warehouseDisplayLabel(row.warehouseCode); // "线边仓" / "总仓" / fallback
 */
export function warehouseDisplayLabel(code: string | null | undefined): string {
  if (!code) return '-';
  if (code === 'WH-WKS') return '线边仓';
  if (code === 'WH-LOG') return '总仓';
  return code; // fallback: surface raw code (forward-compat for future warehouse types)
}

/**
 * Resolve a warehouse's display name — DB `name` is authoritative.
 *
 * Cases:
 *   - name provided        → use it (whatever the customer configured in DB)
 *   - name absent, code known (WH-WKS/WH-LOG) → legacy hardcoded label (fallback)
 *   - name absent, code unknown               → raw code
 *   - both absent                             → "-"
 *
 * 2026-07-02 fix: previously the hardcoded WH-WKS/WH-LOG label ALWAYS won
 * over the server-provided `name`, on the theory that seed data might drift.
 * That made customer-renamed warehouses (LIUSHANMEN renamed WH-LOG to "外仓")
 * permanently invisible in the UI — the hardcoded "总仓" kept showing no
 * matter what the customer set the DB name to. DB name now wins whenever
 * present; the hardcoded label is only a fallback for legacy/unnamed rows.
 */
export function warehouseDisplayName(
  name: string | null | undefined,
  code: string | null | undefined,
): string {
  if (name) return name;
  if (code === 'WH-WKS') return '线边仓';
  if (code === 'WH-LOG') return '总仓';
  if (code) return code;
  return '-';
}

/**
 * Minimal shape needed to resolve a warehouse's DB name from a loaded list.
 */
export interface WarehouseCodeNameLike {
  code?: string | null;
  name?: string | null;
}

/**
 * Resolve a warehouse's DB name by `code`, from an already-loaded warehouse
 * list — for call sites that only persist/receive a bare `warehouseCode`
 * (e.g. sales order line's `sourceWarehouseCode`) and have no warehouse
 * object attached to the row itself.
 *
 * DB name (from `warehouses`) wins whenever a match is found. Falls back to
 * the legacy hardcoded `warehouseDisplayLabel(code)` only when the code
 * isn't in the loaded list (list not loaded yet, warehouse deleted, etc).
 */
export function warehouseNameByCode(
  warehouses: WarehouseCodeNameLike[] | null | undefined,
  code: string | null | undefined,
): string {
  if (!code) return '-';
  const match = warehouses?.find((w) => w.code === code);
  if (match?.name) return match.name;
  return warehouseDisplayLabel(code);
}

/**
 * WarehouseType enum → customer-facing badge label (SP7 六扇门 ERP-lite).
 *
 * Mapping based on FactoryWarehouse.WarehouseType Java enum:
 *   RAW / WIP / FINISHED / LINESIDE / RETURNS / SCRAP / TEMP / QC / OUTSOURCE / TRANSFER / SALTED
 *
 * Four visible badge types per SP7 spec: 原料仓 / 生产仓 / 外仓 / 盐化仓
 */
export type WarehouseTypeBadge = {
  label: string;
  color: string;
};

const WAREHOUSE_TYPE_BADGE_MAP: Record<string, WarehouseTypeBadge> = {
  RAW:       { label: '原料仓', color: '#409eff' },
  WIP:       { label: '生产仓', color: '#e6a23c' },
  FINISHED:  { label: '生产仓', color: '#e6a23c' },
  LINESIDE:  { label: '生产仓', color: '#e6a23c' },
  OUTSOURCE: { label: '外仓',   color: '#909399' },
  TRANSFER:  { label: '外仓',   color: '#909399' },
  SALTED:    { label: '盐化仓', color: '#67c23a' },
  RETURNS:   { label: '退货仓', color: '#f56c6c' },
  SCRAP:     { label: '报废仓', color: '#f56c6c' },
  TEMP:      { label: '临时仓', color: '#909399' },
  QC:        { label: '质检仓', color: '#9b59b6' },
};

export function warehouseTypeBadge(type: string | null | undefined): WarehouseTypeBadge | null {
  if (!type) return null;
  return WAREHOUSE_TYPE_BADGE_MAP[type.toUpperCase()] ?? null;
}

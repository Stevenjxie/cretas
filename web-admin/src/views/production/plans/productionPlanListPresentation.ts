import type { RouteLocationRaw } from 'vue-router';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';

export type ProductionPlanListRow = {
  id?: unknown;
  status?: unknown;
  sourceType?: unknown;
  sourceOrderId?: unknown;
  customerOrderNumber?: unknown;
  plannedQuantity?: unknown;
  plannedUnit?: unknown;
  actualQuantity?: unknown;
};

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatQuantity(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toLocaleString(undefined, {
    maximumFractionDigits: 4,
  });
}

export function formatPlanActualQuantity(row: ProductionPlanListRow | null | undefined): string {
  if (!row) return '—';
  const quantity = finiteNumber(row.actualQuantity);
  if (quantity === null) return '—';
  const unit = canonicalUnitCode(row.plannedUnit);
  return unit
    ? `${formatQuantity(quantity)} ${displayUnit(unit)}`
    : `${formatQuantity(quantity)}（单位未配置）`;
}

export function sourceOrderBusinessNumber(row: ProductionPlanListRow | null | undefined): string | null {
  const number = String(row?.customerOrderNumber ?? '').trim();
  return number || null;
}

export function sourceOrderDisplay(row: ProductionPlanListRow | null | undefined): string {
  const number = sourceOrderBusinessNumber(row);
  if (number) return number;
  const sourceOrderId = String(row?.sourceOrderId ?? '').trim();
  if (sourceOrderId) return '业务订单号未同步';
  return '—';
}

export function sourceOrderTarget(row: ProductionPlanListRow | null | undefined): RouteLocationRaw | null {
  const sourceOrderId = String(row?.sourceOrderId ?? '').trim();
  if (!sourceOrderId) return null;
  return { path: `/sales/orders/${encodeURIComponent(sourceOrderId)}` };
}

/**
 * Factory Production Gold API client.
 *
 * Wraps the 3 /api/smartbi/factory/* endpoints exposed by the Python backend.
 * All endpoints return { success, data, message } envelope.
 *
 * NOTE: pythonFetch auto-converts snake_case → camelCase via transformKeys
 * (see common.ts). Interfaces here describe the camelCase shape callers receive.
 *
 * RBAC: cost fields (totalMaterialCost, totalLaborCost, etc.) are stripped
 *       server-side for non-price roles → returned as null. Frontend additionally
 *       gates the entire cost section behind canViewPrice.
 */
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

// ── Shared query builder ──────────────────────────────────────────────────────

interface FactoryDateQuery {
  factoryId: string;
  startDate?: string; // YYYY-MM-DD; omit = all history
  endDate?: string;
  productTypeId?: string;
}

function _buildQuery(args: FactoryDateQuery): string {
  const p = new URLSearchParams({ factory_id: args.factoryId });
  if (args.startDate) p.set('startDate', args.startDate);
  if (args.endDate) p.set('endDate', args.endDate);
  if (args.productTypeId) p.set('productTypeId', args.productTypeId);
  return p.toString();
}

// ── Response types ────────────────────────────────────────────────────────────

/** One day's cost structure row (all-product rollup) */
export interface CostStructureRow {
  factoryId: string;
  statDate: string; // YYYY-MM-DD
  productTypeId: string | null;
  batchCount: number;
  totalPlannedQty: number | null;
  totalActualQty: number | null;
  totalGoodQty: number | null;
  avgYieldRate: number | null;
  /** null when role lacks price view permission */
  totalMaterialCost: number | null;
  totalLaborCost: number | null;
  totalEquipmentCost: number | null;
  totalOtherCost: number | null;
  totalCost: number | null;
  version: number | null;
  updatedAt: string | null;
}

export interface CostStructureSummary {
  totalMaterialCost: number | null;
  totalLaborCost: number | null;
  totalEquipmentCost: number | null;
  totalOtherCost: number | null;
  totalCost: number | null;
  batchCount: number;
  totalActualQty: number | null;
}

export interface CostStructureData {
  rows: CostStructureRow[];
  summary: CostStructureSummary | Record<string, never>;
}

export interface CostStructureResponse {
  success: boolean;
  data: CostStructureData | null;
  message: string;
}

/** One point on the yield trend */
export interface YieldTrendPoint {
  statDate: string;
  avgYieldRate: number | null;
  batchCount: number;
  totalActualQty: number | null;
  totalGoodQty: number | null;
  /** null when role lacks price view permission */
  totalCost: number | null;
}

export interface YieldTrendData {
  trend: YieldTrendPoint[];
  productTypeId: string | null;
}

export interface YieldTrendResponse {
  success: boolean;
  data: YieldTrendData | null;
  message: string;
}

/** One product's aggregated cost comparison */
export interface ProductCostItem {
  productTypeId: string;
  batchCount: number;
  totalActualQty: number | null;
  totalGoodQty: number | null;
  avgYieldRate: number | null;
  /** null when role lacks price view permission */
  totalMaterialCost: number | null;
  totalLaborCost: number | null;
  totalEquipmentCost: number | null;
  totalOtherCost: number | null;
  totalCost: number | null;
  costPerUnit: number | null;
}

export interface ProductCostCompareData {
  products: ProductCostItem[];
}

export interface ProductCostCompareResponse {
  success: boolean;
  data: ProductCostCompareData | null;
  message: string;
}

// ── API functions ─────────────────────────────────────────────────────────────

/**
 * GET /api/smartbi/factory/cost-structure
 * Returns daily cost breakdown (material/labor/equipment/other) + summary.
 * Dates optional → full history.
 */
export async function getFactoryCostStructure(
  args: FactoryDateQuery,
): Promise<CostStructureResponse> {
  return pythonFetch<CostStructureResponse>(
    `/api/smartbi/factory/cost-structure?${_buildQuery(args)}`,
    { timeoutMs: PYTHON_LLM_TIMEOUT_MS },
  );
}

/**
 * GET /api/smartbi/factory/yield-trend
 * Returns daily yield-rate trend (optionally filtered by product type).
 */
export async function getFactoryYieldTrend(
  args: FactoryDateQuery,
): Promise<YieldTrendResponse> {
  return pythonFetch<YieldTrendResponse>(
    `/api/smartbi/factory/yield-trend?${_buildQuery(args)}`,
    { timeoutMs: PYTHON_LLM_TIMEOUT_MS },
  );
}

/**
 * GET /api/smartbi/factory/product-cost-compare
 * Returns per-product cost aggregation, sorted by totalCost DESC.
 */
export async function getFactoryProductCostCompare(
  args: FactoryDateQuery,
): Promise<ProductCostCompareResponse> {
  return pythonFetch<ProductCostCompareResponse>(
    `/api/smartbi/factory/product-cost-compare?${_buildQuery(args)}`,
    { timeoutMs: PYTHON_LLM_TIMEOUT_MS },
  );
}

// ── T6 Phase-2: Fused timeseries (production domain) ─────────────────────────

/** One fused timeseries row as returned by the resolver. */
export interface FusedTimeseriesRow {
  period: string;
  canonicalField: string;
  valueNum: number | null;
  dims: Record<string, unknown>;
}

export interface FusedTimeseriesResponse {
  success: boolean;
  data: FusedTimeseriesRow[] | null;
  message: string;
}

/** Valid domain values for the fused timeseries endpoint. */
export type FusedDomain = 'production' | 'sales' | 'purchase' | 'inventory';

/**
 * GET /api/smartbi/factory/production-fused
 *
 * Returns timeseries rows from the resolver for the specified domain.
 * ``domain`` defaults to ``'production'`` — callers that omit it get the
 * same behaviour as before (backward-compatible).
 * Fail-open: server returns [] when no data is available.
 */
export async function getFactoryProductionFused(args: {
  factoryId: string;
  start?: string;
  end?: string;
  /** Timeseries domain. Defaults to 'production' when omitted. */
  domain?: FusedDomain;
}): Promise<FusedTimeseriesResponse> {
  const p = new URLSearchParams({ factory_id: args.factoryId });
  if (args.start) p.set('start', args.start);
  if (args.end) p.set('end', args.end);
  if (args.domain) p.set('domain', args.domain);
  return pythonFetch<FusedTimeseriesResponse>(
    `/api/smartbi/factory/production-fused?${p.toString()}`,
    { timeoutMs: PYTHON_LLM_TIMEOUT_MS },
  );
}

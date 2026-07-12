/**
 * Wave2 价格异常威慑引擎 — Python gold API client.
 *
 * Wraps /api/smartbi/gold/price-anomaly/* (Python backend). pythonFetch
 * auto-camelCases responses (transformKeys), so the interfaces below describe
 * the camelCase shape the caller receives — including the {success, data,
 * message} envelope these endpoints emit (unlike the bare gold-reads endpoints).
 *
 * 邓总差异化护城河: 检测同类物料相邻采购单价异常 (洗洁精 110→150、青菜 2.5→2),
 * 要求供应商录入解释 (威慑非处罚), 连续异常累计标高风险供应商。
 */
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

/** 标准异常原因码 (与后端 VALID_REASON_CODES 对齐, Rule 3: dropdown 约束)。 */
export type ReasonCode = 'SEASONAL' | 'MARKET_RISE' | 'SPEC_CHANGE' | 'OTHER';

export const REASON_OPTIONS: Array<{ value: ReasonCode; label: string }> = [
  { value: 'SEASONAL', label: '季节性' },
  { value: 'MARKET_RISE', label: '市场涨价' },
  { value: 'SPEC_CHANGE', label: '规格变化' },
  { value: 'OTHER', label: '其他' },
];

/** 一条检测到的价格异常。 */
export interface PriceAnomaly {
  normalizedName: string;
  ingredientName: string | null;
  supplierId: string | null;
  supplierName: string | null;
  unit: string | null;
  anomalyDeliveryDate: string | null; // YYYY-MM-DD
  oldPrice: number | null; // 立即前一次价 (RBAC: 非 price-view 角色为 null)
  newPrice: number | null; // 最近一次异常价 (RBAC 同上)
  trailingAvg: number | null; // trailing-N 均价 (RBAC 同上)
  deltaPct: number | null; // 偏离率 (率, 始终可见 — 传达威慑信号)
  direction: 'UP' | 'DOWN';
  consecutiveAnomalyCount: number; // 连续同向异常累计 (含本次)
  riskLevel: 'MEDIUM' | 'HIGH'; // 连续三次 → HIGH
}

/** 一条已确认/解释的异常记录。 */
export interface PriceAnomalyAck {
  id: number;
  normalizedName: string;
  ingredientName: string | null;
  supplierId: string | null;
  supplierName: string | null;
  anomalyDeliveryDate: string | null;
  oldPrice: number | null;
  newPrice: number | null;
  deltaPct: number | null;
  reasonCode: ReasonCode;
  reasonNote: string | null;
  acknowledgedBy: string | null;
  createdAt: string | null;
}

interface Envelope<T> {
  success: boolean;
  data: T;
  message?: string;
}

export interface DetectArgs {
  trailingN?: number; // trailing 均价窗口 (default 3)
  epsilonPct?: number; // 异常容限 (default 5%)
  baselineMode?: 'count' | 'days'; // days = 邓总锁定的自身 90 天移动均价
  windowDays?: number; // days 模式窗口天数
}

/** 检测同类物料相邻采购单价异常。 */
export async function detectPriceAnomalies(args: DetectArgs = {}): Promise<PriceAnomaly[]> {
  const p = new URLSearchParams();
  if (args.trailingN !== undefined) p.set('trailing_n', String(args.trailingN));
  if (args.epsilonPct !== undefined) p.set('epsilon_pct', String(args.epsilonPct));
  if (args.baselineMode !== undefined) p.set('baseline_mode', args.baselineMode);
  if (args.windowDays !== undefined) p.set('window_days', String(args.windowDays));
  const qs = p.toString();
  const resp = (await pythonFetch(
    `/api/smartbi/gold/price-anomaly/detect${qs ? `?${qs}` : ''}`,
    { timeoutMs: PYTHON_LLM_TIMEOUT_MS },
  )) as Envelope<PriceAnomaly[]>;
  if (!resp.success) throw new Error(resp.message || '价格异常检测失败');
  return resp.data || [];
}

export interface AckBody {
  normalizedName: string;
  ingredientName?: string | null;
  supplierId?: string | null;
  supplierName?: string | null;
  anomalyDeliveryDate: string; // YYYY-MM-DD
  oldPrice?: number | null;
  newPrice: number;
  deltaPct?: number | null;
  reasonCode: ReasonCode;
  reasonNote?: string | null;
}

/** 录入供应商解释/确认 (威慑非处罚, 幂等)。 */
export async function ackPriceAnomaly(body: AckBody): Promise<{ id: number }> {
  const resp = (await pythonFetch('/api/smartbi/gold/price-anomaly/ack', {
    method: 'POST',
    body: JSON.stringify(body),
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as Envelope<{ id: number }>;
  if (!resp.success) throw new Error(resp.message || '解释录入失败');
  return resp.data;
}

/** 已确认异常列表 (报警去重 + 留痕)。 */
export async function listPriceAnomalyAcks(ingredient?: string): Promise<PriceAnomalyAck[]> {
  const qs = ingredient ? `?ingredient=${encodeURIComponent(ingredient)}` : '';
  const resp = (await pythonFetch(`/api/smartbi/gold/price-anomaly/acks${qs}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as Envelope<PriceAnomalyAck[]>;
  if (!resp.success) throw new Error(resp.message || '已确认列表加载失败');
  return resp.data || [];
}

/** 一个进价趋势点 (供应商采购单价随时间)。 */
export interface SupplierPricePoint {
  deliveryDate: string | null; // YYYY-MM-DD
  unitPrice: number | null; // RBAC: 非 price-view 角色为 null
  supplierName: string | null;
  quantity: number | null;
}

/**
 * 单个食材的进价趋势 — [{deliveryDate, unitPrice, supplierName}] ASC by date。
 * 用于价格异常页的采购单价趋势曲线 (把异常点放回时间序列看得见涨跌)。
 */
export async function getSupplierPriceTrend(ingredient: string, days = 90): Promise<SupplierPricePoint[]> {
  const qs = `?ingredient=${encodeURIComponent(ingredient)}&days=${days}`;
  const resp = (await pythonFetch(`/api/smartbi/gold/supplier-price/trend${qs}`, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as Envelope<SupplierPricePoint[]>;
  if (!resp.success) throw new Error(resp.message || '进价趋势加载失败');
  return resp.data || [];
}

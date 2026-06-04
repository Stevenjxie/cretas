/**
 * #56 价值可视化回馈回路 — 价值快照 API client。
 *
 * 前端直调 Python (D4: 139 Nginx 已反代 47:8083), 与现有 gold 分析 API 一致。
 * pythonFetch 自动 snake_case → camelCase (transformKeys), 故下方接口为 camelCase。
 *
 * 诚实空态: 后端无快照时返回 {success:true, data:null, message:"暂无价值快照..."} —
 * 调用方据此显"暂无数据 [前往上传]", 禁用 0 填 null。
 */
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

/** 月度口径 (本月实测/预估)。金额字段对非金额角色后端会置 null。 */
export interface ValueMonthSummary {
  total: number | null;             // 月度合计 (有金额信号之和; 无 → null)
  shrinkageVariance: number | null; // 档口损溢超标 (本月实测)
  foodCostSavings: number | null;   // 食材成本改善空间 (预估)
  discountSavings: number | null;   // 折扣率改善空间 (预估)
}

/** 年化口径 (恒预估)。 */
export interface ValueAnnualSummary {
  total: number | null;         // 年化合计
  laborRigidity: number | null; // 人工刚性节省 (年化, 预估)
}

export interface ValueSignalSource {
  signal: string;
  label: string;
  amount: number | null;  // null = 暂无数据 (禁用 0)
  kind: 'estimate' | 'measured';
  period: 'month' | 'annual';
}

export interface ValueSummary {
  periodMonth: string;
  storeId: string | null;
  month: ValueMonthSummary;
  annual: ValueAnnualSummary;
  diagnosisCount: number;
  criticalCount: number;
  rxActionCount: number;
  signalSources: ValueSignalSource[];
  confidenceNote: string | null;
  computedAt: string | null;
}

/** 标准 {success, data, message} 信封 (pythonFetch 透传)。 */
export interface ValueSummaryEnvelope {
  success: boolean;
  data: ValueSummary | null;  // null = 暂无快照 (正常空态)
  message: string;
}

/**
 * 拉取最新价值快照 (月度 + 年化两口径, D3)。
 *
 * @param periodMonth 'YYYY-MM'; 省略 = 最新快照
 * @param storeId 门店 ID; 省略 = 全店汇总
 */
export async function getValueSummary(args: {
  periodMonth?: string;
  storeId?: string;
} = {}): Promise<ValueSummaryEnvelope> {
  const p = new URLSearchParams();
  if (args.periodMonth) p.set('period_month', args.periodMonth);
  if (args.storeId) p.set('store_id', args.storeId);
  const qs = p.toString();
  const path = `/api/smartbi/restaurant-value/value-summary${qs ? `?${qs}` : ''}`;
  return (await pythonFetch(path, {
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as ValueSummaryEnvelope;
}

/** 触发当前工厂指定期间重算快照 (手动刷新)。 */
export async function refreshValueSnapshot(args: {
  periodMonth?: string;
  storeId?: string;
} = {}): Promise<{ success: boolean; message: string; data: { totalMonth: number | null; totalAnnual: number | null } | null }> {
  return (await pythonFetch('/api/smartbi/restaurant-value/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ periodMonth: args.periodMonth, storeId: args.storeId }),
    timeoutMs: PYTHON_LLM_TIMEOUT_MS,
  })) as { success: boolean; message: string; data: { totalMonth: number | null; totalAnnual: number | null } | null };
}

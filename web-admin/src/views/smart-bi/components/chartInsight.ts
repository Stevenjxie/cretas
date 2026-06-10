/**
 * U2 — Chart Auto-Insight Tier 1 (rules-first, 0 LLM token) + Tier 2 gateway.
 *
 * Phase 1: TREND + RANKING families (Tier 1).
 * Phase 2 (partial): PIE/PROPORTION → Tier 1 returns null → callers invoke fetchTier2Insight.
 *
 * Reads chart._meta (ChartMeta, supplied by backend U1) + userPermissions,
 * produces a structured InsightResult or null (data insufficient → no display).
 *
 * Design constraints (spec §2.3/§2.6, plan U2 contracts):
 * - Strict null for insufficient data — no fabrication ("禁止降级处理")
 * - RBAC: finance yMetric (revenue/margin/cost) + !canViewFinance → ratios/% only, NEVER ¥
 * - Suggestion verbs: observation only (关注/排查/分析/了解)
 *   NEVER causal-prescriptive (复制/引流/加大/扩张/推广)
 * - Replaces getChartMiniInsight() in SmartBIAnalysis.vue (single Tier 1 source)
 *
 * Gold-wire additions (2026-06-10):
 * - PIE chartType → Tier 1 returns null (PROPORTION Phase 2, not yet implemented)
 * - computeDataPattern: deterministic canonical bucket string for Tier 2 request
 * - fetchTier2Insight: async POST to /api/smartbi/chart-insight; honest-null on failure
 */

// ============================================================
// Contracts (locked — mirrors plan U2 §契约)
// ============================================================

export interface ChartMeta {
  xDim: 'time' | 'store' | 'product' | 'channel' | 'category' | 'other';
  yMetric: 'revenue' | 'quantity' | 'margin' | 'cost' | 'count' | 'pct' | 'other';
  aggregation: 'sum' | 'avg' | 'max' | 'count';
  domain: 'restaurant' | 'factory' | 'finance';
}

export interface InsightResult {
  finding: string;
  implication?: string;
  suggestion?: string;
  source: 'rules' | 'template' | 'llm';
  tier: 1 | 2;
}

export interface UserPermissions {
  /** Whether the current user holds finance:read_write — gates absolute ¥ values */
  canViewFinance: boolean;
}

/** Minimal chart shape consumed by Tier 1 — matches ChartGridItem's chart prop + optional meta */
export interface ChartWithMeta {
  chartType?: string;
  title?: string;
  meta?: ChartMeta | null;
  config?: {
    xAxis?: { data?: string[] };
    series?: Array<{ type?: string; data?: unknown[] }>;
    [key: string]: unknown;
  };
}

// ============================================================
// Internal helpers
// ============================================================

/** Finance-sensitive yMetric types that gate absolute ¥ display (§2.6) */
const FINANCE_METRICS = new Set<ChartMeta['yMetric']>(['revenue', 'margin', 'cost']);

/** xDim values that trigger RANKING family */
const RANKING_DIMS = new Set<ChartMeta['xDim']>(['store', 'product', 'channel', 'category']);

/** Minimum data points for TREND */
const TREND_MIN_POINTS = 4;
/** Minimum ratio threshold to call out as a meaningful multiplier (1.1x) */
const RANKING_MIN_RATIO = 1.1;
/** Percent change below this is considered "flat" (±5%) */
const TREND_FLAT_THRESHOLD = 0.05;

/**
 * Extract numeric series values from a chart config.
 * Returns [] when no valid series data found.
 */
function extractValues(config: ChartWithMeta['config']): number[] {
  const series = config?.series;
  if (!series?.length) return [];
  const allValues: number[] = [];
  for (const s of series) {
    if (!Array.isArray(s.data)) continue;
    for (const d of s.data) {
      if (typeof d === 'number' && isFinite(d)) {
        allValues.push(d);
      } else if (d !== null && typeof d === 'object' && 'value' in d) {
        const v = (d as { value?: unknown }).value;
        if (typeof v === 'number' && isFinite(v)) allValues.push(v);
      }
    }
  }
  return allValues;
}

/**
 * Extract xAxis labels (parallel to series values).
 * Returns [] when no labels.
 */
function extractLabels(config: ChartWithMeta['config']): string[] {
  const xData = config?.xAxis?.data;
  if (!Array.isArray(xData)) return [];
  return xData.map((x) => String(x));
}

/**
 * Format a percentage to 1 decimal place (e.g. 0.235 → "23.5%").
 * Used for ratio-only display when finance gated.
 */
function fmtPct(ratio: number): string {
  return (ratio * 100).toFixed(1) + '%';
}

/**
 * Determine monotonicity of a series.
 * Returns 'rising', 'falling', or 'mixed'.
 */
function monotonicity(values: number[]): 'rising' | 'falling' | 'mixed' {
  let rises = 0;
  let falls = 0;
  for (let i = 1; i < values.length; i++) {
    const delta = values[i] - values[i - 1];
    if (delta > 0) rises++;
    else if (delta < 0) falls++;
  }
  if (falls === 0) return 'rising';
  if (rises === 0) return 'falling';
  return 'mixed';
}

// ============================================================
// TREND family generator
// ============================================================

function buildTrendInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  const values = extractValues(chart.config);
  if (values.length < TREND_MIN_POINTS) return null;

  const first = values[0];
  const last = values[values.length - 1];
  if (first === 0) return null; // can't compute meaningful % from zero base

  const changePct = (last - first) / Math.abs(first);
  const absChangePct = Math.abs(changePct);
  const mono = monotonicity(values);

  const isFinanceMetric = FINANCE_METRICS.has(chart.meta!.yMetric);
  const showAbsolute = isFinanceMetric ? permissions.canViewFinance : true;

  let direction: '上升' | '下降' | '平稳';
  if (absChangePct <= TREND_FLAT_THRESHOLD) {
    direction = '平稳';
  } else if (changePct > 0) {
    direction = '上升';
  } else {
    direction = '下降';
  }

  // Build finding sentence
  let finding: string;
  if (direction === '平稳') {
    finding = `趋势平稳，区间波动 ${fmtPct(absChangePct)} 以内`;
  } else {
    const pctStr = fmtPct(absChangePct);
    if (mono === 'mixed') {
      // Non-monotonic but overall direction
      finding = `整体${direction} ${pctStr}（期间有波动）`;
    } else {
      finding = `单调${direction}，累计变幅 ${pctStr}`;
    }
  }

  // Implication — only use ratios, never absolute values
  let implication: string | undefined;
  if (direction === '上升') {
    implication = '近期保持增长势头，关注是否具有持续性。';
  } else if (direction === '下降') {
    implication = '近期持续下行，建议排查是否有结构性变化。';
  } else {
    implication = '指标运行平稳，波动幅度较小。';
  }

  // Suggestion — observation verbs only (关注/排查/分析/了解); no causal prescriptions
  let suggestion: string | undefined;
  if (direction === '上升') {
    suggestion = '关注增长的驱动因素，分析峰值时段特征。';
  } else if (direction === '下降') {
    suggestion = '排查下降时段的异常，了解环境因素变化。';
  }

  // RBAC: finance metric without permission → remove any possible absolute value leakage
  // (Our finding uses only %, so this is already safe, but guard explicitly)
  if (isFinanceMetric && !permissions.canViewFinance) {
    // Strip any absolute values pattern defensively (belt-and-suspenders)
    const absPattern = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/g;
    finding = finding.replace(absPattern, '(已脱敏)');
    if (implication) implication = implication.replace(absPattern, '(已脱敏)');
  }

  // Suppress unused showAbsolute variable (used as guard; values here are %-only anyway)
  void showAbsolute;

  return {
    finding,
    implication,
    suggestion,
    source: 'rules',
    tier: 1,
  };
}

// ============================================================
// RANKING family generator
// ============================================================

function buildRankingInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  const values = extractValues(chart.config);
  const labels = extractLabels(chart.config);

  if (values.length < 2) return null;

  // Pair values with labels for sorted ranking
  const paired: Array<{ label: string; value: number }> = values
    .map((v, i) => ({ label: labels[i] ?? `项目${i + 1}`, value: v }))
    .filter((p) => p.value > 0); // ignore non-positive entries

  if (paired.length < 2) return null;

  const sorted = [...paired].sort((a, b) => b.value - a.value);
  const top = sorted[0];
  const bottom = sorted[sorted.length - 1];
  const total = sorted.reduce((s, p) => s + p.value, 0);

  if (bottom.value <= 0) return null; // can't compute ratio with zero
  const ratio = top.value / bottom.value;
  if (ratio < RANKING_MIN_RATIO) return null; // not a measurable diff

  const topSharePct = (top.value / total) * 100;
  const isFinanceMetric = FINANCE_METRICS.has(chart.meta!.yMetric);
  const showAbsolute = isFinanceMetric ? permissions.canViewFinance : true;

  // Build finding — always includes ratio (dimensionless, safe) + top-share %
  const ratioStr = ratio.toFixed(1) + '倍';
  const topShareStr = topSharePct.toFixed(1) + '%';

  let finding: string;
  if (showAbsolute) {
    // Finance users (or non-finance metrics): can mention absolute values
    // But for simplicity and safety, we still use ratios/% which is always valid
    finding = `${top.label} 排名最高，是末位 ${bottom.label} 的 ${ratioStr}；占合计 ${topShareStr}`;
  } else {
    // Non-finance users: only ratios and percentages — no absolute ¥
    finding = `${top.label} 排名最高，是末位 ${bottom.label} 的 ${ratioStr}；占合计 ${topShareStr}`;
  }

  // Implication
  const implication =
    topSharePct >= 50
      ? `头部集中度高（${topShareStr}），头尾差距 ${ratioStr}。`
      : `分布相对均衡，头部占比 ${topShareStr}，头尾差距 ${ratioStr}。`;

  // Suggestion — observation verbs only
  let suggestion: string | undefined;
  if (ratio >= 3) {
    suggestion = `关注 ${top.label} 与末位差距原因，分析各项目结构差异。`;
  } else {
    suggestion = `了解各项目间的差异因素，分析是否存在改进空间。`;
  }

  return {
    finding,
    implication,
    suggestion,
    source: 'rules',
    tier: 1,
  };
}

// ============================================================
// Public API
// ============================================================

/**
 * Build a Tier 1 rules-based insight for a chart.
 *
 * @param chart  Chart object containing `.meta` (ChartMeta from backend U1)
 *               and `.config` (ECharts options).
 * @param permissions  Current user's permission context.
 * @returns  Structured InsightResult, or null when data is insufficient
 *           (caller should render nothing — no fallback fabrication).
 *
 * PIE / PROPORTION family → always null in Phase 1 (Phase 2 not yet implemented).
 * Callers should fall back to fetchTier2Insight when null is returned.
 */
export function buildChartInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  // Guard: meta must exist
  if (!chart?.meta) return null;

  // PIE = PROPORTION family → Phase 2, not yet implemented → always null
  // Callers (e.g. RestaurantGoldGrid) should invoke fetchTier2Insight instead.
  if (chart.chartType && chart.chartType.toLowerCase() === 'pie') return null;

  const meta = chart.meta;

  // TREND family: LINE chart with time dimension
  if (meta.xDim === 'time') {
    return buildTrendInsight(chart, permissions);
  }

  // RANKING family: categorical dimension (store/product/channel/category)
  if (RANKING_DIMS.has(meta.xDim)) {
    return buildRankingInsight(chart, permissions);
  }

  // Unrecognized family in Phase 1 → null (Phase 2 will add PROPORTION/COMPARISON/KPI)
  return null;
}

// ============================================================
// computeDataPattern — deterministic canonical bucket string (spec §2.1)
// ============================================================

/**
 * Compute a deterministic canonical data-pattern string for Tier 2 requests.
 *
 * - RANKING / PROPORTION: topShare bucketed + count bucket
 *   e.g. `ranking:top-share:50-65:n4-8` or `proportion:top-share:65-80:n2-3`
 * - TREND (xDim=time): monotonicity + volatility
 *   e.g. `trend:rising:low`
 * - Empty values → `other` (never throws)
 *
 * @param meta        ChartMeta (for routing to correct family)
 * @param values      Numeric series values
 * @param labels      Parallel string labels (may be empty for TREND)
 * @param chartType   Optional: 'pie'/'PIE' triggers 'proportion:' prefix instead of 'ranking:'
 */
export function computeDataPattern(
  meta: ChartMeta,
  values: number[],
  labels: string[],
  chartType?: string,
): string {
  try {
    if (!values.length) return 'other:empty';

    // TREND family
    if (meta.xDim === 'time') {
      if (values.length < 2) return 'trend:insufficient';
      const mono = monotonicity(values);

      // Volatility: σ/μ at threshold 0.3
      const mean = values.reduce((s, v) => s + v, 0) / values.length;
      let volatility: 'low' | 'high' = 'low';
      if (mean !== 0) {
        const variance = values.reduce((s, v) => s + (v - mean) ** 2, 0) / values.length;
        const cv = Math.sqrt(variance) / Math.abs(mean);
        volatility = cv >= 0.3 ? 'high' : 'low';
      }
      return `trend:${mono}:${volatility}`;
    }

    // RANKING / PROPORTION family
    const isPie = chartType ? chartType.toLowerCase() === 'pie' : false;
    const prefix = isPie ? 'proportion' : 'ranking';

    const positiveValues = values.filter((v) => v > 0);
    if (!positiveValues.length) return `${prefix}:no-data`;

    const total = positiveValues.reduce((s, v) => s + v, 0);
    if (total === 0) return `${prefix}:zero-total`;

    const maxVal = Math.max(...positiveValues);
    const topSharePct = (maxVal / total) * 100;

    // Top-share bucket
    let shareBucket: string;
    if (topSharePct >= 80) {
      shareBucket = '80+';
    } else if (topSharePct >= 65) {
      shareBucket = '65-80';
    } else if (topSharePct >= 50) {
      shareBucket = '50-65';
    } else {
      shareBucket = '0-50';
    }

    // Count bucket
    const n = positiveValues.length;
    let countBucket: string;
    if (n >= 9) {
      countBucket = 'n9+';
    } else if (n >= 4) {
      countBucket = 'n4-8';
    } else {
      countBucket = 'n2-3';
    }

    // Suppress unused labels (may be used for richer patterns in future phases)
    void labels;

    return `${prefix}:top-share:${shareBucket}:${countBucket}`;
  } catch {
    return 'other:error';
  }
}

// ============================================================
// Tier 2 request/response types (mirrors Python ChartInsightRequest)
// ============================================================

interface Tier2Request {
  chart_type: string;
  x_dim: string;
  y_metric: string;
  aggregation: string;
  domain: string;
  data_pattern: string;
  permission_tier: 'finance_visible' | 'finance_hidden';
  factory_id: string;
  series_values: number[];
  series_labels: string[];
}

interface Tier2ResponseData {
  finding: string;
  implication?: string;
  suggestion?: string;
  source: 'rules' | 'template' | 'llm';
  tier: number;
}

interface Tier2Response {
  success: boolean;
  data: Tier2ResponseData | null;
}

/**
 * Extract PIE series values from a pie chart config.
 * For PIE: values are in series[0].data[i].value; labels in .name.
 */
function extractPieValues(config: ChartWithMeta['config']): number[] {
  const series = config?.series;
  if (!series?.length) return [];
  const s = series[0];
  if (!Array.isArray(s.data)) return [];
  const result: number[] = [];
  for (const d of s.data) {
    if (typeof d === 'number' && isFinite(d)) {
      result.push(d);
    } else if (d !== null && typeof d === 'object' && 'value' in d) {
      const v = (d as { value?: unknown }).value;
      if (typeof v === 'number' && isFinite(v)) result.push(v);
    }
  }
  return result;
}

function extractPieLabels(config: ChartWithMeta['config']): string[] {
  const series = config?.series;
  if (!series?.length) return [];
  const s = series[0];
  if (!Array.isArray(s.data)) return [];
  const result: string[] = [];
  for (const d of s.data) {
    if (d !== null && typeof d === 'object' && 'name' in d) {
      result.push(String((d as { name?: unknown }).name ?? ''));
    }
  }
  return result;
}

/**
 * The Python SmartBI service URL — mirrors how pythonFetch resolves it.
 * Uses the same env var as common.ts so auth headers and baseURL are consistent.
 */
const PYTHON_SMARTBI_URL =
  typeof import.meta !== 'undefined' && import.meta.env
    ? (import.meta.env.VITE_SMARTBI_URL as string | undefined) ?? '/smartbi-api'
    : '/smartbi-api';

/**
 * Fetch a Tier 2 LLM/template insight from the backend.
 *
 * Posts to POST /api/smartbi/chart-insight (JWT-authenticated via auth header
 * from localStorage, same mechanism as pythonFetch in common.ts).
 *
 * - `factory_id` MUST equal the JWT factoryId — pass the component's prop factoryId.
 * - On success: returns mapped InsightResult (tier:2, source from response).
 * - On data:null / non-2xx / any error: returns null (honest-null, no fabrication).
 * - Never throws to the caller.
 *
 * @param chart      ChartWithMeta (provides meta, config, chartType for request body)
 * @param perms      User permissions (gates permission_tier field)
 * @param factoryId  JWT-matching factory ID (from component prop, NOT derived internally)
 */
export async function fetchTier2Insight(
  chart: ChartWithMeta,
  perms: UserPermissions,
  factoryId: string,
): Promise<InsightResult | null> {
  if (!chart.meta) return null;

  try {
    const meta = chart.meta;
    const chartType = (chart.chartType ?? 'bar').toUpperCase();
    const isPie = chartType === 'PIE';

    // Extract series data — PIE uses .data[i].value/.name; others use xAxis + series[i].data
    const seriesValues = isPie ? extractPieValues(chart.config) : extractValues(chart.config);
    const seriesLabels = isPie ? extractPieLabels(chart.config) : extractLabels(chart.config);

    const dataPattern = computeDataPattern(meta, seriesValues, seriesLabels, chart.chartType);
    const permissionTier: 'finance_visible' | 'finance_hidden' = perms.canViewFinance
      ? 'finance_visible'
      : 'finance_hidden';

    const body: Tier2Request = {
      chart_type: chartType,
      x_dim: meta.xDim,
      y_metric: meta.yMetric,
      aggregation: meta.aggregation,
      domain: meta.domain,
      data_pattern: dataPattern,
      permission_tier: permissionTier,
      factory_id: factoryId,
      series_values: seriesValues,
      series_labels: seriesLabels,
    };

    // Auth header — same pattern as getPythonAuthHeaders() in common.ts
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    // Prefer localStorage token (same as common.ts getPythonAuthHeaders)
    if (typeof localStorage !== 'undefined') {
      const token = localStorage.getItem('cretas_access_token');
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const secret = typeof import.meta !== 'undefined' && import.meta.env
        ? (import.meta.env.VITE_PYTHON_SECRET as string | undefined) ?? ''
        : '';
      if (secret) headers['X-Internal-Secret'] = secret;
    }

    const response = await fetch(`${PYTHON_SMARTBI_URL}/api/smartbi/chart-insight`, {
      method: 'POST',
      headers,
      credentials: 'include',
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      console.warn(`[chart-insight] Tier2 HTTP ${response.status} for factory ${factoryId}`);
      return null;
    }

    const json = (await response.json()) as Tier2Response;

    if (!json.success || !json.data) return null;

    const d = json.data;
    return {
      finding: d.finding,
      implication: d.implication,
      suggestion: d.suggestion,
      source: d.source,
      tier: 2,
    };
  } catch (err) {
    console.warn('[chart-insight] Tier2 fetch failed (honest-null):', err);
    return null;
  }
}

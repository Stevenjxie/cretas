/**
 * U2 — Chart Auto-Insight Tier 1 (rules-first, 0 LLM token) + Tier 2 gateway.
 *
 * Phase 1: TREND + RANKING families (Tier 1).
 * Phase 2 (U2 task): PROPORTION + COMPARISON + KPI families added.
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
 * U2 additions (2026-06-10):
 * - PROPORTION family (PIE chartType): ≥2 slices → "X 占 N%, 前二占 M%"
 * - COMPARISON family: 2 comparable series → "A 较 B 高 N%"
 * - KPI family: actual+target → "达成 N%"; finance guard: only %, NEVER absolute ¥
 * - Removed showAbsolute dead code in RANKING (both branches were identical)
 * - PIE chartType now routes to PROPORTION (no longer always null)
 *
 * U3 addition (2026-06-10):
 * - deriveChartMeta: heuristic ChartMeta inference from plan/column names with expanded word tables
 *
 * Gold-wire additions (2026-06-10):
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
  source: 'rules' | 'template' | 'llm' | 'cache';
  tier: 1 | 2;
  /** G4: corpus row key returned by Tier 2 endpoint; null/undefined for Tier 1 rules-based insights */
  input_hash?: string | null;
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
    series?: Array<{ name?: string; type?: string; data?: unknown[] }>;
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
 *
 * Note: slot values like {topShare} already include the '%' character.
 * Templates must not add a second '%' after these slots.
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

  let direction: '上升' | '下降' | '平稳';
  if (absChangePct <= TREND_FLAT_THRESHOLD) {
    direction = '平稳';
  } else if (changePct > 0) {
    direction = '上升';
  } else {
    direction = '下降';
  }

  // Build finding sentence — always uses % ratios, never absolute ¥
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

  // RBAC: finance metric without permission → strip any possible absolute value leakage
  // Finding uses only % ratios so already safe; belt-and-suspenders regex guard below
  if (isFinanceMetric && !permissions.canViewFinance) {
    const absPattern = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/g;
    finding = finding.replace(absPattern, '(已脱敏)');
    if (implication) implication = implication.replace(absPattern, '(已脱敏)');
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

  // Build finding — always uses ratio (dimensionless, safe) + top-share % — no absolute ¥
  // Both finance and non-finance users get ratio/% output; this is always safe
  const ratioStr = ratio.toFixed(1) + '倍';
  const topShareStr = topSharePct.toFixed(1) + '%';

  const finding = `${top.label} 排名最高，是末位 ${bottom.label} 的 ${ratioStr}；占合计 ${topShareStr}`;

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

  // RBAC belt-and-suspenders: strip any absolute ¥ if finance-gated
  // (finding already only uses ratios/% so this is defensive only)
  if (isFinanceMetric && !permissions.canViewFinance) {
    const absPattern = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/g;
    return {
      finding: finding.replace(absPattern, '(已脱敏)'),
      implication: implication.replace(absPattern, '(已脱敏)'),
      suggestion: suggestion?.replace(absPattern, '(已脱敏)'),
      source: 'rules',
      tier: 1,
    };
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
// PROPORTION family generator (U2 — PIE charts)
// ============================================================

/**
 * Extract PIE series slices: [{label, value}] from config.series[0].data[].
 * Each item may be {name, value} object or bare number.
 */
function extractPieSlices(config: ChartWithMeta['config']): Array<{ label: string; value: number }> {
  const series = config?.series;
  if (!series?.length) return [];
  const s = series[0];
  if (!Array.isArray(s.data)) return [];
  const result: Array<{ label: string; value: number }> = [];
  let idx = 0;
  for (const d of s.data) {
    if (typeof d === 'number' && isFinite(d)) {
      result.push({ label: `项目${idx + 1}`, value: d });
    } else if (d !== null && typeof d === 'object') {
      const obj = d as { name?: unknown; value?: unknown };
      const v = obj.value;
      const n = obj.name;
      if (typeof v === 'number' && isFinite(v)) {
        result.push({ label: String(n ?? `项目${idx + 1}`), value: v });
      }
    }
    idx++;
  }
  return result;
}

/**
 * Build a Tier 1 insight for PROPORTION (PIE) charts.
 *
 * Contract:
 * - ≥2 positive slices required; otherwise null
 * - Finding: "X 占 {topShare}, 前二占 {top2Share}" (always % — no absolute ¥)
 * - Observation verbs only in suggestion; no causal prescriptions
 * - RBAC: finance yMetric + !canViewFinance → output only %, NEVER ¥
 */
export function buildProportionInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  if (!chart?.meta) return null;

  const slices = extractPieSlices(chart.config).filter((s) => s.value > 0);
  if (slices.length < 2) return null;

  const sorted = [...slices].sort((a, b) => b.value - a.value);
  const total = sorted.reduce((s, p) => s + p.value, 0);
  if (total <= 0) return null;

  const top = sorted[0];
  const topShare = (top.value / total) * 100;
  const top2Share = ((sorted[0].value + sorted[1].value) / total) * 100;

  const topShareStr = topShare.toFixed(1) + '%';
  const top2ShareStr = top2Share.toFixed(1) + '%';

  // Finding: always % — no absolute ¥ at all (safe for all permission levels)
  const finding = `${top.label} 占 ${topShareStr}，前二占 ${top2ShareStr}`;

  // Implication
  const implication =
    topShare >= 50
      ? `头部集中，${top.label} 占比超过一半。`
      : `各组成部分分布相对均衡，最大项占比 ${topShareStr}。`;

  // Suggestion — observation verbs only; no causal prescriptions
  let suggestion: string | undefined;
  if (topShare >= 70) {
    suggestion = `关注 ${top.label} 的占比变化趋势，了解各类别构成是否稳定。`;
  } else {
    suggestion = `分析各组成部分的变动，了解结构变化的驱动因素。`;
  }

  // RBAC belt-and-suspenders (finding already has no ¥, defensive only)
  const isFinanceMetric = FINANCE_METRICS.has(chart.meta.yMetric);
  if (isFinanceMetric && !permissions.canViewFinance) {
    const absPattern = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/g;
    return {
      finding: finding.replace(absPattern, '(已脱敏)'),
      implication: implication.replace(absPattern, '(已脱敏)'),
      suggestion: suggestion?.replace(absPattern, '(已脱敏)'),
      source: 'rules',
      tier: 1,
    };
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
// COMPARISON family generator (U2 — 2 comparable series)
// ============================================================

/**
 * Build a Tier 1 insight for COMPARISON charts (2 comparable series).
 *
 * Contract:
 * - Exactly 2 series with at least 1 value each; otherwise null
 * - Finding: "A 较 B 高 N%" (uses only %, NEVER ¥ even for finance metrics)
 * - Observation verbs only; no causal prescriptions
 * - RBAC: finance yMetric + !canViewFinance → only % — NEVER absolute ¥
 */
export function buildComparisonInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  if (!chart?.meta) return null;

  const series = chart.config?.series;
  if (!series || series.length < 2) return null;

  // Extract aggregate (sum) per series
  const seriesValues: Array<{ name: string; total: number }> = [];
  for (let i = 0; i < Math.min(series.length, 2); i++) {
    const s = series[i];
    if (!Array.isArray(s.data)) continue;
    let total = 0;
    for (const d of s.data) {
      if (typeof d === 'number' && isFinite(d)) {
        total += d;
      } else if (d !== null && typeof d === 'object' && 'value' in d) {
        const v = (d as { value?: unknown }).value;
        if (typeof v === 'number' && isFinite(v)) total += v;
      }
    }
    // Series name from config — often stored as series[i].name in ECharts
    const sName = (s as { name?: unknown }).name;
    seriesValues.push({ name: String(sName ?? `系列${i + 1}`), total });
  }

  if (seriesValues.length < 2) return null;

  const [serA, serB] = seriesValues;

  // Need non-zero base to compute meaningful % difference
  if (serB.total === 0 && serA.total === 0) return null;
  if (serB.total === 0) {
    // Can't compute ratio from zero base — return null (no fabrication)
    return null;
  }

  const diffPct = (serA.total - serB.total) / Math.abs(serB.total);
  const absDiffPct = Math.abs(diffPct);

  // Always use % — NEVER absolute ¥ regardless of permissions
  const diffStr = fmtPct(absDiffPct);
  const isHigher = serA.total >= serB.total;
  const higherName = isHigher ? serA.name : serB.name;
  const lowerName = isHigher ? serB.name : serA.name;

  const finding = `${higherName} 较 ${lowerName} 高 ${diffStr}`;

  // Implication
  const implication =
    absDiffPct >= 0.2
      ? `两者差距明显（${diffStr}），了解差异成因有助于优化决策。`
      : `两者差距较小（${diffStr}），指标表现接近。`;

  // Suggestion — observation verbs only
  const suggestion = `分析两者差距的来源，了解各自影响因素是否存在结构差异。`;

  // RBAC belt-and-suspenders
  const isFinanceMetric = FINANCE_METRICS.has(chart.meta.yMetric);
  if (isFinanceMetric && !permissions.canViewFinance) {
    const absPattern = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/g;
    return {
      finding: finding.replace(absPattern, '(已脱敏)'),
      implication: implication.replace(absPattern, '(已脱敏)'),
      suggestion: suggestion.replace(absPattern, '(已脱敏)'),
      source: 'rules',
      tier: 1,
    };
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
// KPI family generator (U2 — actual + target)
// ============================================================

/**
 * Build a Tier 1 insight for KPI charts (actual + target or vs last period).
 *
 * Contract:
 * - Requires exactly 2 series: [actual-series, target-series] or [current, prior]
 * - Finding: "达成 N%" — ONLY %, NEVER absolute ¥ when isFinanceMetric && !canViewFinance
 * - For finance metrics + no finance permission: strip ALL absolute ¥, output only %
 * - Observation verbs only; no causal prescriptions
 *
 * KPI detection: chart.config.series has exactly 2 entries and meta is provided.
 * The caller routes to this family by setting chartType='kpi' or via explicit dispatch.
 */
export function buildKpiInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  if (!chart?.meta) return null;

  const series = chart.config?.series;
  if (!series || series.length < 2) return null;

  // Extract first numeric value from each series (KPI: single-value series)
  function firstValue(s: { type?: string; data?: unknown[] }): number | null {
    if (!Array.isArray(s.data) || s.data.length === 0) return null;
    const d = s.data[0];
    if (typeof d === 'number' && isFinite(d)) return d;
    if (d !== null && typeof d === 'object' && 'value' in d) {
      const v = (d as { value?: unknown }).value;
      if (typeof v === 'number' && isFinite(v)) return v;
    }
    return null;
  }

  const actual = firstValue(series[0]);
  const target = firstValue(series[1]);

  if (actual === null || target === null) return null;
  if (target === 0) return null; // can't compute achievement % from zero target

  const achievementPct = (actual / target) * 100;
  const achievementStr = achievementPct.toFixed(1) + '%';

  const isFinanceMetric = FINANCE_METRICS.has(chart.meta.yMetric);

  // KPI ¥ guard: finance metric + no finance permission → ONLY % output, NEVER ¥
  // The finding itself only uses %, which is always safe
  const finding = `达成 ${achievementStr}`;

  // Implication
  let implication: string;
  if (achievementPct >= 100) {
    implication = `已完成目标（达成率 ${achievementStr}）。`;
  } else if (achievementPct >= 80) {
    implication = `目标完成进度较好（达成率 ${achievementStr}），关注后续趋势。`;
  } else {
    implication = `目标完成率偏低（${achievementStr}），建议排查差距原因。`;
  }

  // Suggestion — observation verbs only
  let suggestion: string | undefined;
  if (achievementPct < 80) {
    suggestion = `排查目标与实际的差距，了解影响达成的关键因素。`;
  } else if (achievementPct >= 100) {
    suggestion = `关注超额完成的持续性，分析是否存在系统性有利因素。`;
  }

  // RBAC: for finance metrics, ensure no absolute ¥ leaks through
  // (our templates already use only %, but apply belt-and-suspenders)
  if (isFinanceMetric && !permissions.canViewFinance) {
    const absPattern = /¥[\d,.]+万?亿?|[\d,.]+万?亿?元/g;
    return {
      finding: finding.replace(absPattern, '(已脱敏)'),
      implication: implication.replace(absPattern, '(已脱敏)'),
      suggestion: suggestion?.replace(absPattern, '(已脱敏)'),
      source: 'rules',
      tier: 1,
    };
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
 * Family routing:
 * - PIE / PROPORTION: buildProportionInsight (≥2 slices → insight; <2 → null)
 * - time xDim: TREND family
 * - store/product/channel/category xDim: RANKING family
 * - 'kpi' chartType (explicit): KPI family
 * - 'comparison' chartType (explicit): COMPARISON family
 * - otherwise: null (Phase 2+ will add more families)
 *
 * Callers should fall back to fetchTier2Insight when null is returned.
 */
export function buildChartInsight(
  chart: ChartWithMeta,
  permissions: UserPermissions,
): InsightResult | null {
  // Guard: meta must exist
  if (!chart?.meta) return null;

  const ct = chart.chartType?.toLowerCase() ?? '';

  // PIE = PROPORTION family (U2: now implemented, not null)
  if (ct === 'pie') {
    return buildProportionInsight(chart, permissions);
  }

  // KPI family: explicit chartType dispatch
  if (ct === 'kpi') {
    return buildKpiInsight(chart, permissions);
  }

  // COMPARISON family: explicit chartType dispatch
  if (ct === 'comparison') {
    return buildComparisonInsight(chart, permissions);
  }

  const meta = chart.meta;

  // TREND family: LINE chart with time dimension
  if (meta.xDim === 'time') {
    return buildTrendInsight(chart, permissions);
  }

  // RANKING family: categorical dimension (store/product/channel/category)
  if (RANKING_DIMS.has(meta.xDim)) {
    return buildRankingInsight(chart, permissions);
  }

  // Unrecognized family → null (no fabrication)
  return null;
}

// ============================================================
// U3 — deriveChartMeta: heuristic ChartMeta inference
// ============================================================

/**
 * Expanded xDim keyword tables (U3).
 * Each entry: [keyword_pattern, xDim value]
 * Checked in order — first match wins.
 */
const X_DIM_KEYWORDS: Array<[RegExp, ChartMeta['xDim']]> = [
  // Time dimension
  [/月|日期|时间|周|季度|年|date|time|week|month|year/i, 'time'],
  // Store dimension
  [/门店|店|store/i, 'store'],
  // Product dimension
  [/产品|品名|物料|原材料|sku|product/i, 'product'],
  // Channel dimension — 渠道/平台 map to channel
  [/渠道|平台|channel|platform/i, 'channel'],
  // Category dimension — 科目/部门/工序/供应商/客户/批次 map to category
  [/分类|类别|科目|部门|工序|供应商|客户|批次|category|dept|process/i, 'category'],
];

/**
 * Expanded yMetric keyword tables (U3).
 * Each entry: [keyword_pattern, yMetric value]
 * Checked in order — first match wins.
 *
 * ORDERING NOTE: Cost-specific compound terms (领料/采购/损耗/费用/应付/应收) must
 * appear BEFORE the generic revenue check because those compound field names like
 * "领料金额" contain the substring 金额 which would otherwise match revenue first.
 */
const Y_METRIC_KEYWORDS: Array<[RegExp, ChartMeta['yMetric']]> = [
  // Percentage / ratio — check first (占比/完成率 are unambiguous)
  [/占比|比率|比例|完成率|达成率|pct|percent|rate/i, 'pct'],
  // Margin / profit — before revenue to avoid "毛利率" matching rate above
  [/毛利|利润|profit|margin/i, 'margin'],
  // Cost-specific terms BEFORE generic 金额 revenue check
  // 损耗/领料/采购/费用/应付/应收 are cost/payable/receivable domain
  [/成本|损耗|领料|采购|费用|应付|应收|cost|waste|purchase|expense|receivable/i, 'cost'],
  // Revenue / income — 金额 here is generic fallback (after cost-specific terms above)
  [/营收|收入|销售额|金额|revenue|income|sales/i, 'revenue'],
  // Quantity — 产量/件数/单数/次数/人次
  [/数量|产量|件数|单数|次数|人次|耗时|count|qty|quantity|num/i, 'quantity'],
];

/**
 * Infer aggregation from column/metric names (heuristic).
 */
function inferAggregation(name: string): ChartMeta['aggregation'] {
  if (/平均|avg|average|mean/i.test(name)) return 'avg';
  if (/最大|最高|max/i.test(name)) return 'max';
  if (/计数|count|数量/i.test(name)) return 'count';
  return 'sum'; // default
}

/**
 * Infer domain from column/context strings.
 */
function inferDomain(text: string): ChartMeta['domain'] {
  if (/餐饮|门店|堂食|外卖|菜品|桌/i.test(text)) return 'restaurant';
  if (/财务|应收|应付|结账|报表|科目/i.test(text)) return 'finance';
  return 'factory'; // default
}

/**
 * Derive a ChartMeta heuristically from plan/column context.
 *
 * @param plan            Plan object — expects {xField?, yFields?, chartType?, title?}
 * @param monthlyColumns  Column names (array of strings) for time-dimension detection
 * @param dataInfo        Free-form context string (title, description, domain hints)
 * @returns ChartMeta when sufficient clues exist, null when clues are insufficient.
 *          Never throws.
 *
 * Word tables (U3 expanded):
 * - xDim: 时间/月/日期 + 门店/产品/品名/物料/原材料 + 渠道/平台 + 科目/部门/工序/供应商/客户/批次
 * - yMetric: 营收/收入 + 毛利/利润 + 成本/损耗/领料/采购/费用/应收/应付 + 数量/件数 + 占比/比率
 *
 * Phase A consumers: U5 only. The 6 explicit gold/dashboard metas are NOT affected
 * (they use hand-filled explicit meta; this function is for unmatched charts).
 */
export function deriveChartMeta(
  plan: {
    xField?: string;
    yFields?: string[];
    chartType?: string;
    title?: string;
  } | null | undefined,
  monthlyColumns: string[],
  dataInfo: string,
): ChartMeta | null {
  try {
    // Collect all text clues
    const xFieldStr = plan?.xField ?? '';
    const yFieldsStr = (plan?.yFields ?? []).join(' ');
    const titleStr = plan?.title ?? '';
    const allText = [xFieldStr, yFieldsStr, titleStr, dataInfo, ...monthlyColumns].join(' ');

    // ------- infer xDim -------
    let xDim: ChartMeta['xDim'] | null = null;

    // Check monthly columns first — common pattern for time series
    if (monthlyColumns.length >= 3) {
      const monthPattern = /^\d{4}[-年]\d{1,2}月?$|^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)/i;
      const hasMonthly = monthlyColumns.some((c) => monthPattern.test(c.trim()));
      if (hasMonthly) xDim = 'time';
    }

    // Check xField keywords
    if (!xDim && xFieldStr) {
      for (const [re, dim] of X_DIM_KEYWORDS) {
        if (re.test(xFieldStr)) {
          xDim = dim;
          break;
        }
      }
    }

    // Fallback: scan allText for xDim clues
    if (!xDim) {
      for (const [re, dim] of X_DIM_KEYWORDS) {
        if (re.test(allText)) {
          xDim = dim;
          break;
        }
      }
    }

    // ------- infer yMetric -------
    let yMetric: ChartMeta['yMetric'] | null = null;

    // Check yFields first (most specific)
    if (yFieldsStr) {
      for (const [re, metric] of Y_METRIC_KEYWORDS) {
        if (re.test(yFieldsStr)) {
          yMetric = metric;
          break;
        }
      }
    }

    // Fallback: scan titleStr + dataInfo
    if (!yMetric) {
      const titleAndInfo = `${titleStr} ${dataInfo}`;
      for (const [re, metric] of Y_METRIC_KEYWORDS) {
        if (re.test(titleAndInfo)) {
          yMetric = metric;
          break;
        }
      }
    }

    // If we can't determine either dimension, return null (insufficient clues)
    if (!xDim || !yMetric) return null;

    // ------- infer aggregation + domain -------
    const aggregation = inferAggregation(yFieldsStr || dataInfo);
    const domain = inferDomain(allText);

    return {
      xDim,
      yMetric,
      aggregation,
      domain,
    };
  } catch {
    return null;
  }
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
  /** G4: corpus row key for the feedback endpoint */
  input_hash?: string | null;
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
  return extractPieSlices(config).map((s) => s.value);
}

function extractPieLabels(config: ChartWithMeta['config']): string[] {
  return extractPieSlices(config).map((s) => s.label);
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
      input_hash: d.input_hash ?? null,  // G4: carry corpus key for feedback
    };
  } catch (err) {
    console.warn('[chart-insight] Tier2 fetch failed (honest-null):', err);
    return null;
  }
}

// ============================================================
// G4 — submitInsightFeedback: POST /api/smartbi/insight/feedback
// ============================================================

/**
 * Submit a 👍/👎 vote for a served corpus insight.
 *
 * @param inputHash   The `input_hash` from the InsightResult (corpus row key)
 * @param vote        'up' or 'down'
 * @param factoryId   Must match the JWT factoryId (same as used in fetchTier2Insight)
 * @returns           true on success, false on any error (never throws)
 */
export async function submitInsightFeedback(
  inputHash: string,
  vote: 'up' | 'down',
  factoryId: string,
): Promise<boolean> {
  try {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (typeof localStorage !== 'undefined') {
      const token = localStorage.getItem('cretas_access_token');
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const secret =
        typeof import.meta !== 'undefined' && import.meta.env
          ? (import.meta.env.VITE_PYTHON_SECRET as string | undefined) ?? ''
          : '';
      if (secret) headers['X-Internal-Secret'] = secret;
    }

    const response = await fetch(`${PYTHON_SMARTBI_URL}/api/smartbi/insight/feedback`, {
      method: 'POST',
      headers,
      credentials: 'include',
      body: JSON.stringify({ input_hash: inputHash, vote, factory_id: factoryId }),
    });

    if (!response.ok) {
      console.warn(`[insight-feedback] HTTP ${response.status} for vote=${vote}`);
      return false;
    }
    const json = await response.json();
    return json?.success === true;
  } catch (err) {
    console.warn('[insight-feedback] submitFeedback failed:', err);
    return false;
  }
}

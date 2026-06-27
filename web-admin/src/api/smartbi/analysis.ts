/**
 * SmartBI API - Analysis Module
 * Analysis, chat, drill-down, cross-sheet, YoY, KPI, financial metrics,
 * chart planning, and the main enrichSheetAnalysis orchestrator.
 */
import {
  pythonFetch,
  request,
  get,
  post,
  getFactoryId,
  getSmartBIBasePath,
  enrichmentLimiter,
  activeControllers,
  humanizeColumnName,
  sanitizeCircledNumbers,
  buildDisplayNameMap,
  formatLargeNumber,
  compressMonthRange,
  SUBTOTAL_SUMMARY_PATTERN,
  KPI_KEYWORD_WEIGHTS,
  PYTHON_SMARTBI_URL,
  getPythonAuthHeaders,
  type AnalysisResult,
  type DrillDownResult,
  type CrossSheetResult,
  type YoYResult,
  type ChartPlanItem,
  type ChartConfig,
  type ColumnSummary,
  type FinancialMetrics,
  type StructuredAIData,
  type EnrichResult,
  type EnrichProgress,
  type SmartKPI,
  type FieldDefinition,
  computeSensitivityFallback,
  PYTHON_LLM_TIMEOUT_MS,
} from './common';

import { getUploadTableData, getUploadFields } from './upload';
import {
  buildChart,
  recommendChart,
  smartRecommendChart,
  batchBuildCharts,
  quickSummary,
  generateInsights,
  generateInsightsStream,
  getForecast,
  getCachedAnalysis,
  saveAnalysisToCache,
} from './python-service';
import { deriveChartMeta, type ChartMeta } from '../../views/smart-bi/components/chartInsight';

// Re-export renameMeaninglessColumns so existing imports work
export { renameMeaninglessColumns } from './data-utils';

// ============================================================
// U5 — attachMetaToCharts: single-pass meta post-processor
// ============================================================

/**
 * Attach ChartMeta to each chart after chartsLane completes (U5).
 *
 * Runs as a single post-processing pass AFTER onProgress has fired — this
 * avoids the onProgress ordering problem where inline meta injection at the
 * 3 build sites would send meta-less charts to the progress callback.
 *
 * Index alignment: charts may be a subset of plans (filtered by config
 * presence + retry replacements).  We match each chart back to the most
 * specific plan by title first, then by xField.  If no plan matches,
 * deriveChartMeta is called with a minimal plan derived from the chart's
 * own xField (best-effort).  When deriveChartMeta returns null the chart
 * gets meta=null — no fabrication.
 *
 * Exported for unit testing.
 */
export function attachMetaToCharts(
  charts: Array<{ chartType: string; title: string; xField?: string; meta?: ChartMeta | null }>,
  plans: Array<{ xField?: string; yFields?: string[]; chartType?: string; title?: string }>,
  monthlyColumns: string[],
  dataInfo: unknown,
): void {
  const dataInfoText = typeof dataInfo === 'string' ? dataInfo : JSON.stringify(dataInfo ?? '');
  // Build lookup maps for efficient matching
  const planByTitle = new Map<string, typeof plans[number]>();
  const planByXField = new Map<string, typeof plans[number]>();
  for (const plan of plans) {
    if (plan.title) planByTitle.set(plan.title, plan);
    if (plan.xField) planByXField.set(plan.xField, plan);
  }

  for (const chart of charts) {
    // Find matching plan: title match first (most specific), xField fallback
    const plan =
      (chart.title ? planByTitle.get(chart.title) : undefined) ??
      (chart.xField ? planByXField.get(chart.xField) : undefined) ??
      // Minimal synthetic plan from the chart itself (retry-replaced charts)
      { xField: chart.xField, yFields: [], chartType: chart.chartType, title: chart.title };

    chart.meta = deriveChartMeta(plan, monthlyColumns, dataInfoText);
  }
}

// ==================== Trend / Comparison / Export ====================

export function getTrendAnalysis(params: {
  metric: string;
  startDate: string;
  endDate: string;
  granularity?: 'day' | 'week' | 'month';
}) {
  return get(`${getSmartBIBasePath()}/analysis/trend`, { params });
}

export function getComparisonAnalysis(params: {
  dimension: string;
  metrics: string[];
  startDate: string;
  endDate: string;
  compareWith?: 'previous_period' | 'same_period_last_year';
}) {
  return post(`${getSmartBIBasePath()}/analysis/comparison`, params);
}

export function exportReport(params: {
  reportType: string;
  format: 'excel' | 'pdf';
  startDate: string;
  endDate: string;
  dimensions?: string[];
}) {
  return request.post(`${getSmartBIBasePath()}/export`, params, {
    responseType: 'blob',
  });
}

// ==================== Chat / Analysis Functions ====================

/**
 * Chat analysis (supports tool calling)
 */
export async function chatAnalysis(params: {
  query: string;
  data?: unknown[];
  fields?: Array<{ original: string; standard: string }>;
  table_type?: string;
  uploadId?: string;
}): Promise<AnalysisResult> {
  try {
    return await pythonFetch('/api/chat/general-analysis', {
      method: 'POST',
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
      body: JSON.stringify({
        query: params.query,
        data: params.data,
        fields: params.fields,
        table_type: params.table_type,
        sheet_id: params.uploadId || undefined,
      })
    }) as AnalysisResult;
  } catch (error) {
    console.error('chatAnalysis 失败:', error);
    return {
      success: false,
      error: error instanceof Error ? error.message : 'Chat 分析请求失败'
    };
  }
}

/**
 * SSE streaming version of chatAnalysis.
 * Returns immediately; calls callbacks as SSE events arrive.
 * Returns an AbortController so the caller can cancel.
 */
export interface ChatStreamCallbacks {
  onStatus?: (status: string) => void;
  onChunk?: (text: string) => void;
  onCharts?: (charts: ChartConfig[]) => void;
  onDone?: (result: AnalysisResult) => void;
  onError?: (error: string) => void;
}

export function chatAnalysisStream(
  params: {
    query: string;
    data?: unknown[];
    fields?: Array<{ original: string; standard: string }>;
    table_type?: string;
    uploadId?: string;
    // Fix 2 (Apr 23 2026): conversation history for pronominal/temporal
    // reference resolution. Send last 3 Q+A (6 messages) so backend LLM
    // can resolve "这个月" / "它" / "那家" from previous turns.
    history?: Array<{ role: 'user' | 'assistant'; content: string }>;
    // v2 conversation memory (Apr 26 2026): server-side session id so the
    // parent answer summary survives page reload and template-cache paths.
    // Caller manages lifecycle via sessionStorage + reset button.
    sessionId?: string;
  },
  callbacks: ChatStreamCallbacks,
): AbortController {
  const controller = new AbortController();

  const url = `${PYTHON_SMARTBI_URL}/api/chat/general-analysis-stream`;
  const body = JSON.stringify({
    query: params.query,
    data: params.data,
    fields: params.fields,
    table_type: params.table_type,
    sheet_id: params.uploadId || undefined,
    session_id: params.sessionId || undefined,
    context: params.history && params.history.length > 0
      ? { history: params.history }
      : undefined,
  });

  (async () => {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: getPythonAuthHeaders(),
        body,
        signal: controller.signal,
      });

      if (!response.ok) {
        callbacks.onError?.(`服务请求失败: ${response.status}`);
        return;
      }

      const reader = response.body?.getReader();
      if (!reader) {
        callbacks.onError?.('浏览器不支持流式响应');
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = '';
      let currentData = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // Parse SSE events from buffer
        const lines = buffer.split('\n');
        buffer = lines.pop() || ''; // Keep incomplete line in buffer

        for (const line of lines) {
          if (line.startsWith('event: ')) {
            currentEvent = line.slice(7).trim();
          } else if (line.startsWith('data: ')) {
            currentData = line.slice(6);
          } else if (line.trim() === '' && currentEvent && currentData) {
            // End of event — dispatch
            _dispatchSSEEvent(currentEvent, currentData, callbacks);
            currentEvent = '';
            currentData = '';
          }
        }
      }

      // Dispatch any remaining event
      if (currentEvent && currentData) {
        _dispatchSSEEvent(currentEvent, currentData, callbacks);
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return; // User cancelled
      }
      console.error('chatAnalysisStream error:', error);
      callbacks.onError?.(error instanceof Error ? error.message : '流式请求失败');
    }
  })();

  return controller;
}

function _dispatchSSEEvent(event: string, data: string, callbacks: ChatStreamCallbacks) {
  try {
    switch (event) {
      case 'status':
        callbacks.onStatus?.(JSON.parse(data));
        break;
      case 'chunk':
        callbacks.onChunk?.(JSON.parse(data));
        break;
      case 'charts': {
        const charts = JSON.parse(data) as ChartConfig[];
        callbacks.onCharts?.(charts);
        break;
      }
      case 'done': {
        const result = JSON.parse(data) as AnalysisResult;
        callbacks.onDone?.(result);
        break;
      }
      case 'error':
        callbacks.onError?.(JSON.parse(data));
        break;
    }
  } catch (e) {
    console.warn('Failed to parse SSE event:', event, data, e);
  }
}

// ==================== NL2SQL ====================

export interface NL2SQLResponse {
  success: boolean;
  sql?: string;
  explanation?: string;
  intent?: string;
  confidence?: number;
  fields?: Array<Record<string, unknown>>;
  result?: Array<Record<string, unknown>>;
  rowCount?: number;
  executionTimeMs?: number;
  warnings?: string[];
  message?: string;
}

export async function nl2sql(params: {
  query: string;
  uploadId: number | string;
  factoryId: string;
  execute?: boolean;
  limit?: number;
}): Promise<NL2SQLResponse> {
  try {
    const res = await pythonFetch('/api/smartbi/nl-to-sql', {
      method: 'POST',
      body: JSON.stringify({
        query: params.query,
        upload_id: Number(params.uploadId),
        factory_id: params.factoryId,
        execute: params.execute ?? false,
        limit: params.limit ?? 100,
      }),
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
    }) as NL2SQLResponse;
    return res;
  } catch (error) {
    console.error('NL2SQL failed:', error);
    return { success: false, message: String(error) };
  }
}

/**
 * Drill-down analysis
 */
export async function drillDownAnalysis(params: {
  dimension: string;
  measures: string[];
  data: unknown[];
  fields: Array<{ original: string; standard: string }>;
  filterValue?: string;
}): Promise<AnalysisResult> {
  try {
    return await pythonFetch('/api/chat/drill-down', {
      method: 'POST',
      body: JSON.stringify(params)
    }) as AnalysisResult;
  } catch (error) {
    console.error('drillDownAnalysis 失败:', error);
    return {
      success: false,
      error: error instanceof Error ? error.message : '下钻分析请求失败'
    };
  }
}

/**
 * Industry benchmark comparison
 */
export async function benchmarkAnalysis(params: {
  metrics: Record<string, number>;
  industry: string;
}): Promise<AnalysisResult> {
  try {
    return await pythonFetch('/api/chat/benchmark', {
      method: 'POST',
      body: JSON.stringify(params)
    }) as AnalysisResult;
  } catch (error) {
    console.error('benchmarkAnalysis 失败:', error);
    return {
      success: false,
      error: error instanceof Error ? error.message : '基准对比请求失败'
    };
  }
}

/**
 * Chart drill-down - called after clicking chart data point
 */
export async function chartDrillDown(params: {
  uploadId: number;
  sheetName: string;
  dimension: string;
  filterValue: string;
  measures: string[];
  data: Record<string, unknown>[];
  hierarchyType?: string;
  currentLevel?: number;
  breadcrumb?: Array<{ dimension: string; value: string }>;
}): Promise<DrillDownResult> {
  try {
    const result = await pythonFetch('/api/chat/drill-down', {
      method: 'POST',
      body: JSON.stringify({
        sheet_id: `upload_${params.uploadId}_${params.sheetName}`,
        dimension: params.dimension,
        filter_value: params.filterValue,
        measures: params.measures,
        data: params.data,
        hierarchy_type: params.hierarchyType,
        current_level: params.currentLevel,
        breadcrumb: params.breadcrumb
      })
    }) as DrillDownResult & {
      availableDimensions?: string[];
    };

    // Generate AI insight if drill-down succeeded
    let aiInsight: string | undefined;
    if (result.success && result.result?.data?.length) {
      const insightRes = await generateInsights({
        data: result.result.data,
        analysisContext: `下钻分析: 维度="${params.dimension}", 筛选值="${params.filterValue}", 报表="${params.sheetName}"`,
        maxInsights: 3
      });
      if (insightRes.success && insightRes.insights?.length) {
        aiInsight = insightRes.insights
          .map(i => `**${i.type}**: ${i.text}${i.recommendation ? `\n建议: ${i.recommendation}` : ''}`)
          .join('\n\n');
      }
    }

    // Note: legacy return uses snake_case keys for backward compat with consumers
    // (typed as DrillDownResult via cast — DrillDownResult declares camelCase but
    // runtime fields below match what callers actually use).
    return {
      success: result.success,
      result: result.result,
      chartConfig: result.chartConfig,
      aiInsight,
      error: result.error,
      available_dimensions: result.availableDimensions,
      hierarchy: result.hierarchy,
      breadcrumb: result.breadcrumb,
      current_level: result.currentLevel,
      max_level: result.maxLevel,
    } as unknown as DrillDownResult;
  } catch (error) {
    console.error('chartDrillDown 失败:', error);
    return {
      success: false,
      error: error instanceof Error ? error.message : '下钻分析请求失败'
    };
  }
}

/**
 * Cross-sheet comprehensive analysis
 */
export async function crossSheetAnalysis(params: {
  uploadIds: number[];
  sheetNames: string[];
}): Promise<CrossSheetResult> {
  try {
    return await pythonFetch('/api/smartbi/cross-sheet-analysis', {
      method: 'POST',
      body: JSON.stringify({
        upload_ids: params.uploadIds,
        sheet_names: params.sheetNames
      })
    }) as CrossSheetResult;
  } catch (error) {
    console.error('crossSheetAnalysis 失败:', error);
    return {
      success: false,
      error: error instanceof Error ? error.message : '综合分析请求失败'
    };
  }
}

/**
 * YoY comparison analysis
 */
export async function yoyComparison(params: {
  uploadId: number;
  compareUploadId?: number;
}): Promise<YoYResult> {
  try {
    return await pythonFetch('/api/smartbi/yoy-comparison', {
      method: 'POST',
      body: JSON.stringify({
        upload_id: params.uploadId,
        compare_upload_id: params.compareUploadId
      })
    }) as YoYResult;
  } catch (error) {
    console.error('yoyComparison failed:', error);
    return {
      success: false,
      currentUploadId: params.uploadId,
      comparison: [],
      error: error instanceof Error ? error.message : 'YoY comparison failed'
    };
  }
}

// ==================== Smart Chart Title + KPI Selection ====================

/**
 * Generate semantic chart title based on chart type and field names
 */
export function generateChartTitle(chartType: string, xField: string, yFields: string[], displayNameMap?: Record<string, string>): string {
  const nameOf = (field: string) => displayNameMap?.[field] || humanizeColumnName(field);
  const xLabel = nameOf(xField);

  const isDateY = yFields.length > 0 && yFields.every(f => /^\d{4}-\d{2}/.test(f) || /^\d{1,2}月$/.test(f));

  // Fix 5: Compact yLabel — collapse 3+ fields into "N项指标"
  let yLabel: string;
  if (isDateY) {
    yLabel = compressMonthRange(yFields);
  } else if (yFields.length === 1) {
    yLabel = nameOf(yFields[0]);
  } else if (yFields.length <= 2) {
    yLabel = yFields.map(nameOf).join('\u3001');
  } else {
    yLabel = `${yFields.length}项指标`;
  }

  const typeVerb: Record<string, string> = {
    'line': '趋势', 'bar': '对比', 'pie': '构成占比', 'waterfall': '增减分析',
    'scatter': '相关性', 'area': '趋势', 'combination': '综合对比',
    'radar': '多维对比', 'bar_horizontal': '排行',
    'pareto': '帕累托分析', 'dual_axis': '双指标对比',
    'sunburst': '层级构成',
  };

  const verb = typeVerb[chartType] || '分析';

  let title: string;
  switch (chartType) {
    case 'line':
      if (xLabel === '月份' || xField === '月份') title = `${yLabel} 月度趋势`;
      else title = isDateY ? `各${xLabel}${yLabel}趋势` : `${yLabel} 变化趋势`;
      break;
    case 'bar':
      if (xLabel === '月份' || xField === '月份') title = `${yLabel} 月度对比`;
      else title = isDateY ? `各${xLabel}${yLabel}对比` : (xLabel ? `各${xLabel}${yLabel}对比` : `${yLabel} 对比分析`);
      break;
    case 'pie':
      title = `${yLabel} 构成占比`;
      break;
    case 'waterfall':
      title = `${yLabel} 增减分析`;
      break;
    case 'scatter':
      title = yFields.length >= 1 && xField
        ? `${xLabel} vs ${nameOf(yFields[0])} 相关性`
        : '散点分布';
      break;
    case 'area':
      title = xField === '月份' ? `${yLabel} 累计趋势` : `${yLabel} 面积分布`;
      break;
    case 'combination':
      title = `${yLabel} 综合对比`;
      break;
    case 'radar':
      title = '多维度对比分析';
      break;
    case 'bar_horizontal':
      title = `${yLabel} 排行`;
      break;
    case 'pareto':
      title = `${yLabel} 帕累托分析`;
      break;
    case 'dual_axis':
      title = `${yLabel} 双指标对比`;
      break;
    case 'sunburst':
      title = `${yLabel} 层级构成`;
      break;
    default:
      title = `${yLabel} ${verb}`;
  }

  // Sanitize: convert any remaining circled numbers ②③... → (2)(3)...
  title = sanitizeCircledNumbers(title);

  // Fix 5: Hard limit 30 characters
  if (title.length > 30) {
    title = title.slice(0, 27) + '...';
  }
  return title;
}

/**
 * Smart KPI extraction
 */
export function getSmartKPIs(
  kpiSummary: { rowCount: number; columnCount: number; columns: ColumnSummary[] },
  financialMetrics?: FinancialMetrics | null,
  displayNameMap?: Record<string, string>
): SmartKPI[] {
  const numericTypes = ['int64', 'float64', 'numeric', 'number', 'int32', 'float32'];
  // Apr 24 2026 — include rating cols (aggStrategy='mean') even though their
  // sum is null. They display as 平均星级=4.83 etc. ID cols (aggStrategy='none')
  // explicitly excluded.
  const numericCols = (kpiSummary.columns || []).filter(c => {
    if (!numericTypes.includes(c.type)) return false;
    if (c.aggStrategy === 'none') return false;
    if (c.aggStrategy === 'mean' && c.mean != null) return true;
    return c.sum != null;
  });

  if (numericCols.length === 0) {
    return [{
      title: '数据行数',
      value: kpiSummary.rowCount,
      unit: '行',
      status: 'info',
      displayMode: 'default',
      sparklineData: []
    }];
  }

  // ========== Phase 1: Financial ratio KPIs from computed metrics ==========
  const ratioKPIs: SmartKPI[] = [];

  if (financialMetrics) {
    if (financialMetrics.revenue) {
      const rev = financialMetrics.revenue.total;
      const { displayValue, unit } = formatLargeNumber(rev);
      const lastMom = financialMetrics.mom?.filter(m => m.change !== null).slice(-1)[0];
      ratioKPIs.push({
        title: '营业收入',
        value: displayValue,
        unit,
        trend: lastMom?.change != null ? (lastMom.change > 0 ? 'up' : lastMom.change < 0 ? 'down' : 'flat') : undefined,
        trendValue: lastMom?.change != null ? `环比${lastMom.change > 0 ? '+' : ''}${lastMom.change.toFixed(1)}%` : undefined,
        changeRate: lastMom?.change ?? undefined,
        status: 'success',
        displayMode: financialMetrics.monthlyRevenue && financialMetrics.monthlyRevenue.length >= 2 ? 'sparkline' : 'default',
        sparklineData: financialMetrics.monthlyRevenue || [],
      });
    }

    if (financialMetrics.grossMargin != null) {
      const gap = financialMetrics.gaps['grossMargin'] ?? 0;
      const isHealthy = gap >= 0;
      ratioKPIs.push({
        title: '毛利率',
        value: (financialMetrics.grossMargin * 100).toFixed(1),
        unit: '%',
        trendValue: `行业${(financialMetrics.industryBenchmark.grossMargin * 100).toFixed(0)}%`,
        status: isHealthy ? 'success' : gap > -0.05 ? 'warning' : 'danger',
        displayMode: 'default',
        sparklineData: [],
        benchmarkLabel: `行业均值${(financialMetrics.industryBenchmark.grossMargin * 100).toFixed(0)}%`,
        benchmarkGap: gap * 100,
      });
    }

    if (financialMetrics.netMargin != null) {
      const gap = financialMetrics.gaps['netMargin'] ?? 0;
      const isHealthy = gap >= 0;
      ratioKPIs.push({
        title: '净利率',
        value: (financialMetrics.netMargin * 100).toFixed(1),
        unit: '%',
        trendValue: `行业${(financialMetrics.industryBenchmark.netMargin * 100).toFixed(0)}%`,
        status: isHealthy ? 'success' : financialMetrics.netMargin >= 0 ? 'warning' : 'danger',
        displayMode: financialMetrics.monthlyNetProfit && financialMetrics.monthlyNetProfit.length >= 2 ? 'sparkline' : 'default',
        sparklineData: financialMetrics.monthlyNetProfit || [],
        benchmarkLabel: `行业均值${(financialMetrics.industryBenchmark.netMargin * 100).toFixed(0)}%`,
        benchmarkGap: gap * 100,
      });
    }

    if (financialMetrics.netProfit && ratioKPIs.length < 4) {
      const np = financialMetrics.netProfit.total;
      const { displayValue, unit } = formatLargeNumber(np);
      ratioKPIs.push({
        title: '净利润',
        value: displayValue,
        unit,
        status: np >= 0 ? 'success' : 'danger',
        displayMode: financialMetrics.monthlyNetProfit && financialMetrics.monthlyNetProfit.length >= 2 ? 'sparkline' : 'default',
        sparklineData: financialMetrics.monthlyNetProfit || [],
      });
    }

    if (financialMetrics.expenseRatio != null && ratioKPIs.length < 4) {
      ratioKPIs.push({
        title: '费用率',
        value: (financialMetrics.expenseRatio * 100).toFixed(1),
        unit: '%',
        trendValue: `基准${(financialMetrics.industryBenchmark.expenseRatio * 100).toFixed(0)}%`,
        status: financialMetrics.expenseRatio <= financialMetrics.industryBenchmark.expenseRatio ? 'success' : 'warning',
        displayMode: 'default',
        sparklineData: [],
      });
    }
  }

  if (ratioKPIs.length >= 3) {
    return ratioKPIs.slice(0, 4);
  }

  // ========== Phase 2: Column-based KPIs ==========
  interface ScoredCol {
    col: ColumnSummary;
    score: number;
    matchedKeyword: string;
    status: 'success' | 'warning' | 'danger' | 'info' | 'default';
  }
  const scored: ScoredCol[] = numericCols.map(col => {
    const extCol = col;
    let score = 0;
    let matchedKeyword = '';
    let status: 'success' | 'warning' | 'danger' | 'info' | 'default' = 'default';

    for (const [keyword, config] of Object.entries(KPI_KEYWORD_WEIGHTS)) {
      if (col.name.includes(keyword)) {
        score += config.weight;
        matchedKeyword = keyword;
        status = config.status;
        break;
      }
    }

    // Score basis: sum for amount-style measures, mean for ratings (smaller scale).
    const isRating = col.aggStrategy === 'mean';
    const scoreBasis = isRating ? Math.abs(col.mean || 0) : Math.abs(col.sum || 0);
    if (scoreBasis > 0) score += Math.min(Math.log10(scoreBasis + 1) * 5, 30);
    if (extCol.trend) score += 10;
    // Bias rating cols up so they show prominently for review xlsx (where
    // they're often the only meaningful KPI candidate).
    if (isRating) score += 15;

    return { col: extCol, score, matchedKeyword, status };
  });

  scored.sort((a, b) => b.score - a.score);

  const coveredNames = new Set(ratioKPIs.map(k => k.title));
  // Dedup: skip columns whose base name (without parenthetical suffixes) is already covered
  const baseNameOf = (name: string) => name.replace(/[（(].*[)）]$/, '').trim();
  const seenBases = new Set([...coveredNames].map(baseNameOf));
  const remaining = scored.filter(s => {
    const base = baseNameOf(s.col.name);
    if (coveredNames.has(s.col.name) || seenBases.has(base)) return false;
    seenBases.add(base);
    return true;
  });

  const slotsNeeded = 4 - ratioKPIs.length;
  const topN = remaining.slice(0, slotsNeeded);

  // Helper: compute sparkline-based MoM trend, skipping trailing zeros
  function computeSparklineTrend(sparkline: number[]): number | null {
    if (!sparkline || sparkline.length < 2) return null;
    const spark = sparkline;
    // Skip trailing zeros to find last meaningful data point
    let lastIdx = spark.length - 1;
    while (lastIdx > 0 && spark[lastIdx] === 0) lastIdx--;
    if (lastIdx >= 1) {
      const last = spark[lastIdx];
      const prev = spark[lastIdx - 1];
      if (prev !== 0) {
        return Math.round(((last - prev) / Math.abs(prev)) * 1000) / 10;
      } else {
        return last !== 0 ? 100 : 0;
      }
    }
    return 0;
  }

  const columnKPIs: SmartKPI[] = topN.map(({ col, status }) => {
    // Rating cols (aggStrategy='mean') display the mean, not sum.
    const isRating = col.aggStrategy === 'mean';
    const val = isRating ? (col.mean || 0) : (col.sum || 0);
    const extCol = col;

    let displayValue: string | number;
    let unit: string;
    if (isRating) {
      // Ratings are 1-5 scale — show 2 decimals, no 万/亿 unit
      displayValue = val.toFixed(2);
      unit = '分';
    } else {
      ({ displayValue, unit } = formatLargeNumber(val));
    }

    let trendPct = extCol.trendPercent;

    // If trendPercent from Python is null/undefined but sparkline exists, compute from sparkline
    if (trendPct == null && extCol.sparkline && extCol.sparkline.length >= 2) {
      trendPct = computeSparklineTrend(extCol.sparkline);
    }
    // If sparkline exists, always prefer sparkline-based MoM over Python's first-vs-last
    else if (extCol.sparkline && extCol.sparkline.length >= 2) {
      trendPct = computeSparklineTrend(extCol.sparkline);
    }

    // Guard: extreme values (abs >= 80%) are likely data artifacts (e.g., row-based financial
    // data where first_val=revenue and last_val=net_profit, not a real time trend)
    if (trendPct != null && Math.abs(trendPct) >= 80) {
      trendPct = null;
    }

    const trend: 'up' | 'down' | 'flat' | undefined =
      trendPct != null ? (trendPct > 0 ? 'up' : trendPct < 0 ? 'down' : 'flat') : undefined;
    const trendValue = trendPct != null ? `${trendPct > 0 ? '+' : ''}${trendPct}%` : undefined;

    let finalStatus = status;
    if (finalStatus === 'default' && trend) {
      finalStatus = trend === 'up' ? 'success' : trend === 'down' ? 'danger' : 'info';
    }

    const baseTitle = displayNameMap?.[col.name] || humanizeColumnName(col.name);
    // Rating cols get "平均" prefix to make it clear we're showing mean (not sum).
    const finalTitle = isRating ? `平均${baseTitle}` : baseTitle;

    return {
      title: finalTitle,
      value: displayValue,
      unit,
      trend,
      trendValue,
      changeRate: trendPct != null ? trendPct : undefined,
      status: finalStatus,
      displayMode: (extCol.sparkline && extCol.sparkline.length >= 2 ? 'sparkline' : 'default') as 'sparkline' | 'default',
      sparklineData: extCol.sparkline || [],
      // Ratings need 2 decimals (4.83 vs 5 — huge semantic diff). Others default 0.
      precision: isRating ? 2 : 0,
    };
  });

  // Safety check: if ALL column KPIs ended up with no trend (all nulled out), that's fine.
  // If ALL have exactly the same extreme negative trend, it's a mapping bug — clear them.
  const withTrend = columnKPIs.filter(k => k.changeRate != null);
  if (withTrend.length >= 2) {
    const allSame = withTrend.every(k => k.changeRate === withTrend[0].changeRate);
    if (allSame && Math.abs(withTrend[0].changeRate!) >= 50) {
      // All identical extreme trend = data artifact, clear all trends
      for (const kpi of columnKPIs) {
        kpi.changeRate = undefined;
        kpi.trend = undefined;
        kpi.trendValue = undefined;
      }
    }
  }

  return [...ratioKPIs, ...columnKPIs].slice(0, 4);
}

// ==================== Financial Metrics Pre-computation ====================

/**
 * Pre-compute financial metrics from profit/loss statement data
 */
export function computeFinancialMetrics(
  data: Record<string, unknown>[],
  monthlyColumns: string[],
  labelField: string
): FinancialMetrics | null {
  if (!data.length) return null;

  const keywordMap: Array<{ keywords: string[]; field: keyof Pick<FinancialMetrics, 'revenue' | 'cost' | 'grossProfit' | 'netProfit'> }> = [
    { keywords: ['营业收入', '主营业务收入'], field: 'revenue' },
    { keywords: ['营业成本', '主营业务成本'], field: 'cost' },
    { keywords: ['毛利润', '毛利'], field: 'grossProfit' },
    { keywords: ['净利润'], field: 'netProfit' },
  ];
  const expenseKeywords = ['销售费用', '管理费用', '财务费用', '研发费用'];
  const allFinancialKeywords = [
    ...keywordMap.flatMap(m => m.keywords),
    ...expenseKeywords,
  ];

  let effectiveLabelField = labelField;
  const sample = data;

  if (effectiveLabelField) {
    let labelFieldMatches = 0;
    for (const row of sample) {
      const val = String(row[effectiveLabelField] ?? '').replace(/[\s\u3000]+/g, '');
      if (val && allFinancialKeywords.some(kw => val.includes(kw))) {
        labelFieldMatches++;
      }
    }
    if (labelFieldMatches < 1) {
      // console.warn(`[computeFinancialMetrics] labelField "${effectiveLabelField}" has ${labelFieldMatches} keyword matches, forcing auto-detection`);
      effectiveLabelField = '';
    }
  }

  if (!effectiveLabelField) {
    const allKeys = Object.keys(data[0] || {});
    let bestCol = '';
    let bestMatches = 0;
    for (const key of allKeys) {
      let matchCount = 0;
      for (const row of sample) {
        const val = String(row[key] ?? '').replace(/[\s\u3000]+/g, '');
        if (val && allFinancialKeywords.some(kw => val.includes(kw))) {
          matchCount++;
        }
      }
      if (matchCount > bestMatches) {
        bestMatches = matchCount;
        bestCol = key;
      }
    }
    if (bestMatches >= 1) {
      effectiveLabelField = bestCol;
    }
  }

  if (!effectiveLabelField) {
    const firstCol = Object.keys(data[0] || {})[0];
    if (firstCol) {
      let firstColMatches = 0;
      for (const row of sample) {
        const val = String(row[firstCol] ?? '').replace(/[\s\u3000]+/g, '');
        if (val && allFinancialKeywords.some(kw => val.includes(kw))) {
          firstColMatches++;
        }
      }
      if (firstColMatches >= 1) {
        // console.warn(`[computeFinancialMetrics] first-column fallback: "${firstCol}" has ${firstColMatches} keyword matches`);
        effectiveLabelField = firstCol;
      }
    }
  }

  if (!effectiveLabelField) return null;

  const result: FinancialMetrics = {
    expenses: [],
    industryBenchmark: { grossMargin: 0.30, netMargin: 0.05, expenseRatio: 0.20 },
    gaps: {},
  };

  for (const row of data) {
    const rawLabel = String(row[effectiveLabelField] ?? '');
    const label = rawLabel.replace(/[\s\u3000]+/g, '').trim();
    if (!label) continue;

    const values: Record<string, number> = {};
    let total = 0;
    for (const [key, val] of Object.entries(row)) {
      if (key === effectiveLabelField) continue;
      const num = typeof val === 'number' ? val : parseFloat(String(val));
      if (!isNaN(num)) {
        values[key] = num;
        total += num;
      }
    }
    if (Object.keys(values).length === 0) continue;

    const finRow = { label, values, total };

    for (const { keywords, field } of keywordMap) {
      if (keywords.some(kw => label.includes(kw)) && !result[field]) {
        (result as unknown as Record<string, unknown>)[field] = finRow;
        break;
      }
    }

    if (expenseKeywords.some(kw => label.includes(kw))) {
      result.expenses.push(finRow);
    }
  }

  if (result.revenue && result.revenue.total !== 0) {
    const rev = Math.abs(result.revenue.total);

    if (result.cost) {
      result.grossMargin = (result.revenue.total - result.cost.total) / rev;
      result.gaps['grossMargin'] = result.grossMargin - result.industryBenchmark.grossMargin;
    }

    if (result.netProfit) {
      result.netMargin = result.netProfit.total / rev;
      result.gaps['netMargin'] = result.netMargin - result.industryBenchmark.netMargin;
    }

    if (result.expenses.length > 0) {
      const totalExpense = result.expenses.reduce((sum, e) => sum + e.total, 0);
      result.expenseRatio = totalExpense / rev;
      result.gaps['expenseRatio'] = result.expenseRatio - result.industryBenchmark.expenseRatio;
    }

    if (monthlyColumns.length >= 2 && result.revenue) {
      result.monthlyRevenue = monthlyColumns.map(m => result.revenue!.values[m] ?? 0);
      result.mom = monthlyColumns.map((m, i) => {
        if (i === 0 || !result.monthlyRevenue) return { month: humanizeColumnName(m), change: null };
        const prev = result.monthlyRevenue[i - 1];
        const curr = result.monthlyRevenue[i];
        return {
          month: humanizeColumnName(m),
          change: prev !== 0 ? ((curr - prev) / Math.abs(prev)) * 100 : null,
        };
      });
    }

    if (monthlyColumns.length >= 2 && result.netProfit) {
      result.monthlyNetProfit = monthlyColumns.map(m => result.netProfit!.values[m] ?? 0);
    }
  }

  if (!result.revenue && !result.netProfit && result.expenses.length === 0) return null;
  return result;
}

/**
 * Format financial metrics as LLM-readable context text
 */
function formatFinancialContext(metrics: FinancialMetrics): string {
  const parts: string[] = ['预计算财务指标:'];

  const fmt = (val: number) => {
    if (Math.abs(val) >= 1e8) return `${(val / 1e8).toFixed(2)}亿`;
    if (Math.abs(val) >= 1e4) return `${(val / 1e4).toFixed(2)}万`;
    return val.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
  };

  if (metrics.revenue) parts.push(`- 营业收入: ${fmt(metrics.revenue.total)}`);
  if (metrics.cost) parts.push(`- 营业成本: ${fmt(metrics.cost.total)}`);
  if (metrics.grossProfit) parts.push(`- 毛利: ${fmt(metrics.grossProfit.total)}`);
  if (metrics.netProfit) parts.push(`- 净利润: ${fmt(metrics.netProfit.total)}`);
  for (const exp of metrics.expenses) {
    parts.push(`- ${exp.label}: ${fmt(exp.total)}`);
  }

  if (metrics.grossMargin != null) {
    const gap = metrics.gaps['grossMargin'];
    parts.push(`- 毛利率: ${(metrics.grossMargin * 100).toFixed(1)}% (行业参考30%, ${gap && gap >= 0 ? '高于' : '低于'}行业${Math.abs((gap ?? 0) * 100).toFixed(1)}pp)`);
  }
  if (metrics.netMargin != null) {
    const gap = metrics.gaps['netMargin'];
    parts.push(`- 净利率: ${(metrics.netMargin * 100).toFixed(1)}% (行业参考5%, ${gap && gap >= 0 ? '高于' : '低于'}行业${Math.abs((gap ?? 0) * 100).toFixed(1)}pp)`);
  }
  if (metrics.expenseRatio != null) {
    parts.push(`- 费用率: ${(metrics.expenseRatio * 100).toFixed(1)}% (行业参考20%)`);
  }

  if (metrics.mom && metrics.mom.length > 0) {
    const momParts = metrics.mom
      .filter(m => m.change !== null)
      .map(m => `${m.month}:${m.change! > 0 ? '+' : ''}${m.change!.toFixed(1)}%`);
    if (momParts.length > 0) {
      parts.push(`- 收入环比: ${momParts.join(', ')}`);
    }
  }

  return parts.join('\n');
}

// ==================== Data Utilities (imported from data-utils) ====================
import {
  cleanDataForChart,
  detectMonthlyColumns,
  detectNumericColumns,
  detectLabelField,
  selectSummaryYFields,
  transposeForTimeSeries,
  formatMonthLabel,
  extractTextContext,
  detectAndPrefixSubTables,
  renameMeaninglessColumns,
} from './data-utils';

// ==================== Chart Plan Builder ====================

/**
 * Build multi-chart plan (3-5 diverse charts)
 */
function buildChartPlan(
  cleanedData: Record<string, unknown>[],
  recommendations: Array<{ chartType: string; xField?: string; yFields?: string[]; priority: number }>,
  dataInfo: { numericColumns: string[]; categoricalColumns: string[]; dateColumns: string[] },
  monthlyColumns: string[],
  labelField: string,
  sheetIndex?: number,
  displayNameMap?: Record<string, string>
): ChartPlanItem[] {
  const plans: ChartPlanItem[] = [];
  const usedTypes = new Set<string>();

  // Local helper: pass displayNameMap to all chart title generation
  const titleOf = (type: string, x: string, y: string[]) => generateChartTitle(type, x, y, displayNameMap);

  const cleanLabel = (label: string): string => {
    const gm = label.match(/^(第\d+组)-.+$/);
    if (gm) return gm[1];
    return label.replace(/-\d+$/, '').replace(/\.0$/, '');
  };

  const numCols = dataInfo.numericColumns || [];

  // ========== Phase 1: Core strategies requiring labelField ==========

  // Strategy 1: Monthly columns -> time series line chart
  if (monthlyColumns.length >= 2 && labelField) {
    const tsData = transposeForTimeSeries(cleanedData, monthlyColumns, labelField);
    if (tsData.length > 0) {
      const seriesKeys = Object.keys(tsData[0]).filter(k => k !== '月份');
      if (seriesKeys.length > 0) {
        plans.push({
          chartType: 'line',
          data: tsData,
          xField: '月份',
          yFields: seriesKeys.slice(0, 5),
          title: titleOf('line', '月份', seriesKeys.slice(0, 5))
        });
        usedTypes.add('line');
      }
    }
  }

  // Strategy 2: Categorical bar chart
  if (labelField && numCols.length > 0 && !usedTypes.has('bar')) {
    const yFields = selectSummaryYFields(numCols, labelField, 3);
    if (yFields.length > 0) {
      const seenLabels = new Set<string>();
      const filteredData = cleanedData.filter(row => {
        const label = row[labelField];
        if (label == null) return false;
        const key = String(label).trim();
        if (SUBTOTAL_SUMMARY_PATTERN.test(key)) return false;
        if (seenLabels.has(key)) return false;
        seenLabels.add(key);
        return true;
      });
      const primaryY = yFields[0];
      const sortedData = [...filteredData].sort((a, b) => {
        const va = Number(a[primaryY]) || 0;
        const vb = Number(b[primaryY]) || 0;
        return vb - va;
      });
      const chartData = sortedData.slice(0, 20).map(row => {
        const label = String(row[labelField] || '');
        const cleaned = cleanLabel(label);
        return { ...row, [labelField]: cleaned.length > 15 ? cleaned.substring(0, 15) + '...' : cleaned };
      });
      if (chartData.length > 0) {
        const barTitle = filteredData.length > 20
          ? titleOf('bar', labelField, yFields) + ' (前20项)'
          : titleOf('bar', labelField, yFields);
        plans.push({
          chartType: 'bar', data: chartData, xField: labelField,
          yFields, title: barTitle
        });
        usedTypes.add('bar');
      }
    }
  }

  // Strategy 3: Pie chart
  if (labelField && numCols.length > 0 && !usedTypes.has('pie')) {
    let valueField = numCols.find(c => /合计|总计|sum|total/i.test(c));
    if (!valueField) {
      valueField = monthlyColumns.length > 0
        ? monthlyColumns[monthlyColumns.length - 1]
        : numCols[0];
    }
    if (valueField) {
      const pieData = cleanedData
        .filter(row => row[labelField] != null && row[valueField!] != null)
        .filter(row => !SUBTOTAL_SUMMARY_PATTERN.test(String(row[labelField]).trim()))
        .filter(row => { const v = Number(row[valueField!]); return !isNaN(v) && v > 0; })
        .slice(0, 10)
        .map(row => ({ ...row, [labelField]: cleanLabel(String(row[labelField] || '')) }));
      if (pieData.length >= 2) {
        plans.push({
          chartType: 'pie', data: pieData, xField: labelField,
          yFields: [valueField],
          title: titleOf('pie', labelField, [valueField]) + (pieData.length < cleanedData.filter(r => r[labelField] != null && r[valueField!] != null).filter(r => Number(r[valueField!]) > 0).length ? ` (前${pieData.length}项)` : '')
        });
        usedTypes.add('pie');
      }
    }
  }

  // Strategy 4: From Python recommendations
  for (const rec of recommendations) {
    if (plans.length >= 6) break; // Reserve 1-2 slots for Phase 2 diversification
    if (usedTypes.has(rec.chartType)) continue;
    if (!rec.xField || !rec.yFields?.length) continue;

    if (rec.chartType === 'line' && !monthlyColumns.includes(rec.xField)) {
      continue;
    }

    let chartData = cleanedData.filter(row => row[rec.xField!] != null);
    if (rec.chartType === 'bar' && rec.yFields.length > 0) {
      const py = rec.yFields[0];
      chartData = [...chartData].sort((a, b) => (Number(b[py]) || 0) - (Number(a[py]) || 0));
    }
    chartData = chartData.slice(0, 30);
    if (chartData.length < 2) continue;

    if (rec.xField && labelField) {
      chartData = chartData.map(row => ({ ...row, [rec.xField!]: cleanLabel(String(row[rec.xField!] || '')) }));
    }

    plans.push({
      chartType: rec.chartType, data: chartData, xField: rec.xField,
      yFields: rec.yFields, title: titleOf(rec.chartType, rec.xField!, rec.yFields!)
    });
    usedTypes.add(rec.chartType);
  }

  // Strategy 5: Horizontal bar (monthly ranking)
  if (plans.length < 4 && monthlyColumns.length > 0 && labelField && !usedTypes.has('bar_horizontal')) {
    const tsData = transposeForTimeSeries(cleanedData, monthlyColumns, labelField);
    if (tsData.length > 0) {
      const firstSeries = Object.keys(tsData[0]).find(k => k !== '月份');
      if (firstSeries) {
        const sorted = [...tsData].sort((a, b) => Number(b[firstSeries] || 0) - Number(a[firstSeries] || 0));
        plans.push({
          chartType: 'bar_horizontal', data: sorted, xField: '月份',
          yFields: [firstSeries], title: titleOf('bar_horizontal', '月份', [firstSeries])
        });
        usedTypes.add('bar_horizontal');
      }
    }
  }

  // ========== Phase 2: Diversification ==========
  const phase2Strategies: Array<{ type: string; tryAdd: () => void }> = [
    { type: 'waterfall', tryAdd: () => {
      if (!labelField || numCols.length === 0 || usedTypes.has('waterfall')) return;
      const seenWfLabels = new Set<string>();
      const wfDataRaw = cleanedData.filter(row => {
        const label = row[labelField];
        if (label == null) return false;
        const key = String(label).trim();
        if (SUBTOTAL_SUMMARY_PATTERN.test(key)) return false;
        if (seenWfLabels.has(key)) return false;
        seenWfLabels.add(key);
        return true;
      });
      if (wfDataRaw.length < 3) return;
      const yField = numCols.find(c => c !== labelField) || numCols[0];
      if (!yField) return;
      let wfData = wfDataRaw;
      if (wfData.length > 30) {
        wfData = [...wfDataRaw]
          .sort((a, b) => Math.abs(Number(b[yField] || 0)) - Math.abs(Number(a[yField] || 0)))
          .slice(0, 25);
      }
      wfData = wfData.map(row => ({ ...row, [labelField]: cleanLabel(String(row[labelField] || '')) }));
      plans.push({
        chartType: 'waterfall', data: wfData, xField: labelField,
        yFields: [yField], title: titleOf('waterfall', labelField, [yField])
      });
      usedTypes.add('waterfall');
    }},
    { type: 'area', tryAdd: () => {
      if (monthlyColumns.length < 2 || !labelField || usedTypes.has('area')) return;
      const tsData = transposeForTimeSeries(cleanedData, monthlyColumns, labelField);
      if (tsData.length === 0) return;
      const seriesKeys = Object.keys(tsData[0]).filter(k => k !== '月份');
      if (seriesKeys.length === 0) return;
      plans.push({
        chartType: 'area', data: tsData, xField: '月份',
        yFields: seriesKeys.slice(0, 3), title: titleOf('area', '月份', seriesKeys.slice(0, 3))
      });
      usedTypes.add('area');
    }},
    { type: 'combination', tryAdd: () => {
      if (!labelField || numCols.length < 2 || usedTypes.has('combination')) return;
      const yFields = selectSummaryYFields(numCols, labelField, 2);
      if (yFields.length < 2) return;
      const chartData = cleanedData.filter(row => row[labelField] != null).slice(0, 15)
        .map(row => ({ ...row, [labelField]: cleanLabel(String(row[labelField] || '')) }));
      if (chartData.length < 2) return;
      plans.push({
        chartType: 'combination', data: chartData, xField: labelField,
        yFields, title: titleOf('combination', labelField, yFields)
      });
      usedTypes.add('combination');
    }},
    { type: 'pareto', tryAdd: () => {
      if (!labelField || numCols.length === 0 || usedTypes.has('pareto')) return;
      const seenLabels = new Set<string>();
      const paretoData = cleanedData.filter(row => {
        const label = row[labelField];
        if (label == null) return false;
        const key = String(label).trim();
        if (SUBTOTAL_SUMMARY_PATTERN.test(key)) return false;
        if (seenLabels.has(key)) return false;
        seenLabels.add(key);
        return true;
      });
      if (paretoData.length < 3 || paretoData.length > 30) return;
      const yField = numCols.find(c => c !== labelField) || numCols[0];
      if (!yField) return;
      const sorted = [...paretoData].sort((a, b) => Math.abs(Number(b[yField] || 0)) - Math.abs(Number(a[yField] || 0)));
      const chartData = sorted.slice(0, 20).map(row => ({
        ...row, [labelField]: cleanLabel(String(row[labelField] || ''))
      }));
      plans.push({
        chartType: 'pareto', data: chartData, xField: labelField,
        yFields: [yField], title: titleOf('pareto', labelField, [yField])
      });
      usedTypes.add('pareto');
    }},
    { type: 'dual_axis', tryAdd: () => {
      if (!labelField || numCols.length < 2 || usedTypes.has('dual_axis')) return;
      const yFields = selectSummaryYFields(numCols, labelField, 2);
      if (yFields.length < 2) return;
      // Check if fields have different scales (ratio > 10 or < 0.1 suggests different units)
      const vals0 = cleanedData.map(r => Math.abs(Number(r[yFields[0]]) || 0)).filter(v => v > 0);
      const vals1 = cleanedData.map(r => Math.abs(Number(r[yFields[1]]) || 0)).filter(v => v > 0);
      if (vals0.length === 0 || vals1.length === 0) return;
      const avg0 = vals0.reduce((a, b) => a + b, 0) / vals0.length;
      const avg1 = vals1.reduce((a, b) => a + b, 0) / vals1.length;
      const ratio = avg0 / avg1;
      if (ratio > 0.1 && ratio < 10) return; // similar scale, skip dual_axis
      const chartData = cleanedData.filter(row => row[labelField] != null).slice(0, 20)
        .map(row => ({ ...row, [labelField]: cleanLabel(String(row[labelField] || '')) }));
      if (chartData.length < 2) return;
      plans.push({
        chartType: 'dual_axis', data: chartData, xField: labelField,
        yFields, title: titleOf('dual_axis', labelField, yFields)
      });
      usedTypes.add('dual_axis');
    }},
    { type: 'bar_horizontal', tryAdd: () => {
      if (!labelField || numCols.length === 0 || usedTypes.has('bar_horizontal')) return;
      // Use horizontal bar when many categories (>8) or labels are long
      const uniqueLabels = new Set(cleanedData.map(r => String(r[labelField] ?? '')).filter(Boolean));
      if (uniqueLabels.size < 8) return;
      const yField = numCols.find(c => c !== labelField) || numCols[0];
      if (!yField) return;
      const sorted = [...cleanedData].filter(row => row[labelField] != null)
        .sort((a, b) => (Number(b[yField] || 0)) - (Number(a[yField] || 0)))
        .slice(0, 15)
        .map(row => ({ ...row, [labelField]: cleanLabel(String(row[labelField] || '')) }));
      if (sorted.length < 3) return;
      plans.push({
        chartType: 'bar_horizontal', data: sorted, xField: labelField,
        yFields: [yField], title: titleOf('bar_horizontal', labelField, [yField])
      });
      usedTypes.add('bar_horizontal');
    }},
    { type: 'sunburst', tryAdd: () => {
      if (numCols.length === 0 || usedTypes.has('sunburst')) return;
      // Sunburst needs hierarchical data: at least 2 categorical-like columns + 1 numeric
      const catCols = Object.keys(cleanedData[0] || {}).filter(k => {
        if (numCols.includes(k)) return false;
        const uniqueVals = new Set(cleanedData.map(r => String(r[k] ?? '')).filter(Boolean));
        return uniqueVals.size >= 2 && uniqueVals.size <= 30;
      });
      if (catCols.length < 2) return;
      const yField = numCols.find(c => /合计|总计|sum|total|金额|收入|amount/i.test(c)) || numCols[0];
      if (!yField) return;
      const chartData = cleanedData.filter(row => row[catCols[0]] != null && Number(row[yField]) > 0).slice(0, 50);
      if (chartData.length < 3) return;
      plans.push({
        chartType: 'sunburst', data: chartData, xField: catCols[0],
        yFields: [yField], seriesField: catCols[1],
        title: titleOf('sunburst', catCols[0], [yField])
      });
      usedTypes.add('sunburst');
    }},
  ];

  const offset = (sheetIndex ?? 0) % phase2Strategies.length;
  const rotatedPhase2 = [...phase2Strategies.slice(offset), ...phase2Strategies.slice(0, offset)];
  for (const strategy of rotatedPhase2) {
    if (plans.length >= 7) break;
    strategy.tryAdd();
  }

  // ========== Phase 3: Fallback strategies (no labelField needed) ==========
  if (plans.length === 0 && numCols.length > 0) {
    const indexedData = cleanedData.slice(0, 20).map((row, i) => ({
      ...row,
      __rowIndex: `#${i + 1}`
    }));
    const yFields = numCols.slice(0, 3);
    plans.push({
      chartType: 'bar', data: indexedData, xField: '__rowIndex',
      yFields, title: titleOf('bar', '#行号', yFields)
    });
    usedTypes.add('bar');

    if (numCols.length >= 2) {
      plans.push({
        chartType: 'scatter', data: cleanedData.slice(0, 50), xField: numCols[0],
        yFields: [numCols[1]], title: titleOf('scatter', numCols[0], [numCols[1]])
      });
      usedTypes.add('scatter');
    }

    if (numCols.length >= 3) {
      const radarData = cleanedData.slice(0, 5).map((row, i) => ({
        ...row,
        __rowIndex: `#${i + 1}`
      }));
      plans.push({
        chartType: 'radar', data: radarData, xField: '__rowIndex',
        yFields: numCols.slice(0, 6), title: titleOf('radar', '__rowIndex', numCols.slice(0, 6))
      });
      usedTypes.add('radar');
    }
  }

  return plans.slice(0, 7);
}

// ==================== Main Enrichment Orchestrator ====================

/** In-flight enrichment deduplication: concurrent calls for the same uploadId share one promise */
const inFlightEnrichments = new Map<number, Promise<EnrichResult>>();

/**
 * Sheet data enrichment - multi-chart dashboard version
 * Orchestration: clean -> recommend + quickSummary (parallel) -> buildChartPlan -> batchBuild -> forecast -> insights
 */
export async function enrichSheetAnalysis(
  uploadId: number,
  forceRefresh = false,
  onProgress?: (progress: EnrichProgress) => void
): Promise<EnrichResult> {
  // Deduplication: if already in-flight for this uploadId, share the promise
  if (!forceRefresh && inFlightEnrichments.has(uploadId)) {
    return inFlightEnrichments.get(uploadId)!;
  }

  const promise = _doEnrichSheetAnalysis(uploadId, forceRefresh, onProgress);
  if (!forceRefresh) {
    inFlightEnrichments.set(uploadId, promise);
    promise.finally(() => inFlightEnrichments.delete(uploadId));
  }
  return promise;
}

async function _doEnrichSheetAnalysis(
  uploadId: number,
  forceRefresh: boolean,
  onProgress?: (progress: EnrichProgress) => void
): Promise<EnrichResult> {
  // Cache-first
  // Apr 25 2026 (Task C / PROD-1 fix): cache may be PARTIAL — when populated
  // by the Java γ-2c afterCommit hook, only kpiSummary is filled (cheap polars
  // compute, no LLM). In that case render KPIs immediately so the user sees
  // them in <1s, then continue running the full enrichment pipeline below to
  // fill charts/aiAnalysis/structuredAI in the background. A FULL cache
  // (created by saveAnalysisToCache after the LLM pipeline finishes) returns
  // _partial=false and we short-circuit as before.
  if (!forceRefresh) {
    try {
      const cached = await getCachedAnalysis(uploadId);
      if (cached && cached.success) {
        if (cached._partial) {
          // Render KPIs early so the user sees something in <1s, then fall
          // through to the full pipeline so charts + AI insights still arrive.
          if (cached.kpiSummary) {
            onProgress?.({
              phase: 'kpi',
              partial: {
                kpiSummary: cached.kpiSummary as EnrichResult['kpiSummary'],
              },
            });
          }
        } else {
          onProgress?.({ phase: 'complete', partial: cached as unknown as Partial<EnrichResult> });
          return cached as unknown as EnrichResult;
        }
      }
    } catch (e) {
      // console.warn('[Cache] Error checking cache, proceeding with enrichment:', e);
    }
  }

  await enrichmentLimiter.acquire();
  const timings: Record<string, number> = {};
  const tick = (label: string, t0: number) => { timings[label] = Math.round(performance.now() - t0); };
  const abortController = new AbortController();
  const prevController = activeControllers.get(`enrich-${uploadId}`);
  if (prevController) prevController.abort();
  activeControllers.set(`enrich-${uploadId}`, abortController);
  try {
    // 1. Get persisted table data + field definitions (parallel)
    let t0 = performance.now();
    const [tableRes, fieldsRes] = await Promise.all([
      getUploadTableData(uploadId, 0, 2000),
      getUploadFields(uploadId).catch(() => ({ success: false, data: [] as FieldDefinition[] })),
    ]);
    if (!tableRes.success || !tableRes.data?.data?.length) {
      return { success: false, error: '无法获取上传数据' };
    }
    const rawData = tableRes.data.data as Record<string, unknown>[];
    // Build displayNameMap from persisted field definitions
    const fieldDefs = Array.isArray(fieldsRes?.data) ? fieldsRes.data : [];
    const displayNameMap: Record<string, string> = fieldDefs.length > 0
      ? buildDisplayNameMap(fieldDefs)
      : {};
    tick('getUploadTableData+fields', t0);

    // 2. Clean column names
    t0 = performance.now();
    const renamedData = renameMeaninglessColumns(rawData);
    tick('rename', t0);

    // 3. Extract text context
    t0 = performance.now();
    const textContext = extractTextContext(renamedData);
    tick('extractTextContext', t0);

    // 4. Clean data
    t0 = performance.now();
    const indexedData = renamedData.map((row, i) => ({ ...row, __rawIdx: i }));
    const cleanedWithIdx = cleanDataForChart(indexedData);
    tick('cleanData', t0);
    if (!cleanedWithIdx.length) {
      return { success: false, error: '清洗后无有效数据' };
    }

    const rawIdxArray = cleanedWithIdx.map(r => r.__rawIdx as number);
    let cleanedData = cleanedWithIdx.map(({ __rawIdx, ...rest }) => rest);

    onProgress?.({ phase: 'data', partial: { rawData: cleanedData } });

    // 5. Parallel: smart-recommend (LLM) + summary; use allSettled to prevent cascade failure
    t0 = performance.now();
    const [smartRecSettled, summarySettled] = await Promise.allSettled([
      smartRecommendChart(
        { data: cleanedData.slice(0, 100), maxRecommendations: 7 },
        abortController.signal
      ).catch(() => ({ success: false } as { success: false })),
      quickSummary(cleanedData, abortController.signal, uploadId)
    ]);
    const smartRecRes = smartRecSettled.status === 'fulfilled'
      ? smartRecSettled.value
      : { success: false as const };
    const summaryRes = summarySettled.status === 'fulfilled'
      ? summarySettled.value
      : { success: false as const, columnTypes: {} as Record<string, string>, stats: {} as Record<string, unknown> };
    let recRes: Awaited<ReturnType<typeof recommendChart>>;
    if (smartRecRes.success && 'recommendations' in smartRecRes && smartRecRes.recommendations?.length) {
      recRes = {
        success: true,
        recommendations: smartRecRes.recommendations,
        dataInfo: 'dataInfo' in smartRecRes ? smartRecRes.dataInfo : undefined
      };
    } else {
      recRes = await recommendChart(cleanedData, abortController.signal).catch((): Awaited<ReturnType<typeof recommendChart>> => ({
        success: false, recommendations: [], dataInfo: undefined
      }));
    }
    tick('recommend+summary', t0);

    const recommendations = recRes.success && recRes.recommendations?.length
      ? recRes.recommendations
      : [{ chartType: 'bar', xField: undefined, yFields: undefined, priority: 1 }];

    const dataInfo = recRes.dataInfo || { numericColumns: [], categoricalColumns: [], dateColumns: [] };

    // 6. Detect columns + build chart plan
    t0 = performance.now();
    const allKeys = Object.keys(cleanedData[0]);
    const monthlyColumns = detectMonthlyColumns(allKeys);
    const enhancedNumericCols = detectNumericColumns(cleanedData, dataInfo.numericColumns, allKeys);
    const enhancedDataInfo = { ...dataInfo, numericColumns: enhancedNumericCols };
    const labelField = detectLabelField(cleanedData, dataInfo.categoricalColumns, enhancedNumericCols, allKeys);
    cleanedData = detectAndPrefixSubTables(renamedData, cleanedData, labelField, enhancedNumericCols, rawIdxArray);
    if (labelField) {
      cleanedData = cleanedData.filter(row => {
        const label = String(row[labelField] ?? '').trim();
        return !SUBTOTAL_SUMMARY_PATTERN.test(label);
      });
    }
    const plans = buildChartPlan(cleanedData, recommendations, enhancedDataInfo, monthlyColumns, labelField, uploadId, displayNameMap);
    tick('detect+buildPlan', t0);

    // Notify KPI ready — user sees KPI cards within ~2-3s
    const kpiSummaryEarly = summaryRes.success
      ? { rowCount: summaryRes.rowCount, columnCount: summaryRes.columnCount, columns: summaryRes.columns }
      : undefined;
    onProgress?.({ phase: 'kpi', partial: { kpiSummary: kpiSummaryEarly, chartsTotal: plans.length } });

    // 7.6 Pre-compute financial metrics (sync, no await — needed early for insights)
    t0 = performance.now();
    const financialMetrics = computeFinancialMetrics(cleanedData, monthlyColumns, labelField);
    tick('computeFinancialMetrics', t0);

    // 7+8 PARALLEL (Apr 25 2026 perf E1a):
    //   - Left lane: batchBuildCharts (step 7) → forecast (step 7.5)
    //   - Right lane: generateInsightsStream (step 8)
    // Both lanes only depend on cleanedData / plans / financialMetrics — all
    // computed above. Insights does NOT depend on chart configs. Saves 3-8s
    // wall (insights LLM ~5-10s overlaps with chart build ~3-5s).
    // onProgress callbacks fire from both lanes concurrently — that's OK,
    // each phase ('chart-single' / 'ai-streaming' / 'ai') is independent.
    t0 = performance.now();

    // ---------- Left lane: charts + forecast ----------
    const chartsLane = (async (): Promise<Array<{ chartType: string; title: string; config: Record<string, unknown>; xField?: string; anomalies?: Record<string, unknown>; meta?: ChartMeta | null }>> => {
      let charts: Array<{ chartType: string; title: string; config: Record<string, unknown>; xField?: string; anomalies?: Record<string, unknown>; meta?: ChartMeta | null }> = [];
      if (plans.length > 0) {
        const batchRes = await batchBuildCharts(plans, abortController.signal);
        if (batchRes.success && batchRes.charts?.length) {
          charts = batchRes.charts
            .map((c, i) => ({
              chartType: c.chartType,
              title: plans[i]?.title || '数据分析',
              config: c.config,
              xField: plans[i]?.xField || '',
              anomalies: c.anomalies
            }))
            .filter(c => c.config);
        }

        if (charts.length === 0) {
          for (const plan of plans) {
            const res = await buildChart({
              chartType: plan.chartType,
              data: plan.data,
              xField: plan.xField,
              yFields: plan.yFields,
              title: plan.title
            });
            if (res.success && res.option) {
              charts.push({ chartType: plan.chartType, title: plan.title, config: res.option, xField: plan.xField });
            }
          }
        }
      }

      // 7.1 Auto-retry empty charts with alternative types
      const isSeriesEmpty = (config: Record<string, unknown>): boolean => {
        if (!config || Object.keys(config).length === 0) return true;
        const series = config.series as unknown[] | undefined;
        if (!series) return true;
        const arr = Array.isArray(series) ? series : [series];
        return arr.every((rawS: unknown) => {
          const s = rawS as Record<string, unknown>;
          return !s.data || (s.data as unknown[]).length === 0;
        });
      };
      const emptyIndices = charts
        .map((c, i) => isSeriesEmpty(c.config) ? i : -1)
        .filter(i => i >= 0);
      if (emptyIndices.length > 0) {
        const existingTypes = charts.map(c => c.chartType).filter(Boolean) as string[];
        try {
          const retryRec = await smartRecommendChart(
            { data: cleanedData.slice(0, 100), excludeTypes: existingTypes, maxRecommendations: emptyIndices.length },
            abortController.signal
          ).catch(() => ({ success: false } as { success: false }));
          if (retryRec.success && 'recommendations' in retryRec && retryRec.recommendations?.length) {
            for (let ri = 0; ri < Math.min(emptyIndices.length, retryRec.recommendations.length); ri++) {
              const rec = retryRec.recommendations[ri];
              const idx = emptyIndices[ri];
              const res = await buildChart({
                chartType: rec.chartType,
                data: cleanedData.slice(0, 200),
                xField: rec.xField,
                yFields: rec.yFields,
                title: rec.title || charts[idx].title
              });
              if (res.success && res.option && !isSeriesEmpty(res.option)) {
                charts[idx] = {
                  chartType: rec.chartType,
                  title: rec.title || charts[idx].title,
                  config: res.option,
                  xField: rec.xField
                };
              }
            }
          }
        } catch { /* non-critical: keep original empty charts */ }
      }

      // Notify charts ready (before forecast — forecast mutates in-place but
      // user sees the base chart as soon as batch build completes)
      if (charts.length) {
        onProgress?.({ phase: 'chart-single', partial: { charts: [...charts] } });
      }

      // 7.5 Forecast trend lines for line charts (mutates chart.config in-place)
      for (const chart of charts) {
        if (chart.chartType !== 'line') continue;
        const config = chart.config as Record<string, unknown>;
        const series = config?.series;
        if (!Array.isArray(series)) continue;

        const firstSeries = series.find((rawS: unknown) => {
          const s = rawS as Record<string, unknown>;
          return s.type === 'line' && Array.isArray(s.data);
        }) as { data: unknown[]; name?: string } | undefined;
        if (!firstSeries) continue;
        const numericData = (firstSeries.data.filter((v: unknown) => typeof v === 'number' && !isNaN(v as number))) as number[];
        if (numericData.length < 5) continue;

        try {
          const forecastRes = await getForecast(numericData, 3);
          if (forecastRes.success && forecastRes.predictions?.length) {
            const xData = (config.xAxis as { data?: unknown[] } | undefined)?.data;
            if (Array.isArray(xData)) {
              for (let i = 0; i < forecastRes.predictions.length; i++) {
                xData.push(`预测${i + 1}`);
              }
            }

            const padded = new Array(numericData.length).fill(null);
            padded[padded.length - 1] = numericData[numericData.length - 1];
            series.push({
              name: `${firstSeries.name ?? ''}(预测)`,
              type: 'line',
              data: [...padded, ...forecastRes.predictions.map((v: number) => Math.round(v * 100) / 100)],
              lineStyle: { type: 'dashed', width: 2 },
              symbol: 'diamond',
              symbolSize: 6,
              itemStyle: { color: '#9ca3af' }
            });

            if (forecastRes.lowerBound?.length && forecastRes.upperBound?.length) {
              const basePad = new Array(numericData.length).fill(null);
              series.push({
                name: '置信下界',
                type: 'line',
                data: [...basePad, ...forecastRes.lowerBound.map((v: number) => Math.round(v * 100) / 100)],
                lineStyle: { opacity: 0 },
                symbol: 'none',
                silent: true,
                z: -1
              });
              series.push({
                name: '置信区间',
                type: 'line',
                data: [...basePad, ...forecastRes.upperBound.map((v: number) => Math.round(v * 100) / 100)],
                lineStyle: { opacity: 0 },
                symbol: 'none',
                areaStyle: { color: 'rgba(156,163,175,0.18)', origin: 'auto' },
                silent: true,
                z: -1
              });
            }
          }
        } catch {
          // Forecast is optional
        }
      }

      // U5: single-pass meta post-processing — runs AFTER onProgress (line ~1648)
      // so the progress callback always fires before meta is attached (charts
      // were already sent to the UI without meta; this enriches the final result).
      attachMetaToCharts(charts, plans, monthlyColumns, dataInfo);

      return charts;
    })();

    // ---------- Right lane: AI insights (SSE streaming) ----------
    const insightsLane = (async (): Promise<{ aiAnalysis: string; structuredAI: StructuredAIData | undefined }> => {
      let aiAnalysis = '';
      let structuredAI: StructuredAIData | undefined;
      // Humanize column keys in data sent to LLM so AI text uses display names
      const keyMap: Record<string, string> = {};
      for (const k of allKeys) {
        keyMap[k] = displayNameMap[k] || humanizeColumnName(k);
      }
      const insightData = cleanedData.slice(0, 100).map(row => {
        const mapped: Record<string, unknown> = {};
        for (const k of allKeys) {
          mapped[keyMap[k]] = row[k];
        }
        return mapped;
      });
      const columnNames = Object.values(keyMap).join(', ');
      const contextParts = [`数据列: ${columnNames}`];
      if (textContext) {
        contextParts.push(textContext);
      }
      if (financialMetrics) {
        contextParts.push(formatFinancialContext(financialMetrics));
      }
      const insightRes = await generateInsightsStream({
        data: insightData,
        analysisContext: contextParts.join('\n'),
        maxInsights: 5,
        signal: abortController.signal,
        onChunk: (chunk: string) => {
          // Emit each SSE text chunk so the UI can render progressively (typewriter effect)
          onProgress?.({ phase: 'ai-streaming', partial: { aiStreamChunk: chunk } });
        }
      });
      if (insightRes.success && insightRes.insights?.length) {
        const metaInsight = insightRes.insights.find(i => i.type === '_meta');
        const normalInsights = insightRes.insights.filter(i => i.type !== '_meta');

        if (metaInsight) {
          const meta = metaInsight as Record<string, unknown>;
          structuredAI = {
            executiveSummary: (meta.executive_summary as string) || (meta.text as string) || '',
            riskAlerts: (meta.risk_alerts as StructuredAIData['riskAlerts']) || [],
            opportunities: (meta.opportunities as StructuredAIData['opportunities']) || [],
            sensitivityAnalysis: (meta.sensitivity_analysis as StructuredAIData['sensitivityAnalysis']) || []
          };
        }

        // Fallback: compute sensitivity analysis from financial metrics if LLM didn't return it
        if (structuredAI && (!structuredAI.sensitivityAnalysis || structuredAI.sensitivityAnalysis.length === 0) && financialMetrics) {
          structuredAI.sensitivityAnalysis = computeSensitivityFallback(financialMetrics);
        }

        const typeLabels: Record<string, string> = {
          trend: '趋势分析', anomaly: '异常检测', comparison: '对比分析',
          kpi: '关键指标', recommendation: '改进建议', forecast: '预测',
        };
        aiAnalysis = normalInsights
          .map(i => {
            const label = typeLabels[i.type] || i.type;
            let line = `${label}: ${i.text}`;
            if (i.recommendation) line += ` 建议: ${i.recommendation}`;
            return line;
          })
          .join('\n\n');
      }

      // Notify AI analysis ready (fires as soon as insights lane completes,
      // independent of chart lane)
      onProgress?.({ phase: 'ai', partial: { aiAnalysis: aiAnalysis || undefined, structuredAI } });

      return { aiAnalysis, structuredAI };
    })();

    // Await both lanes — Promise.all blocks until both done (each lane already
    // fires its own onProgress for streaming UI updates).
    const [charts, insightsResult] = await Promise.all([chartsLane, insightsLane]);
    const { aiAnalysis, structuredAI } = insightsResult;
    tick('charts+insights(parallel)', t0);

    // 9. Assemble KPI summary
    // Trust Python /api/insight/quick-summary response shape — it already returns
    // correct names + types + aggStrategy (after Apr 25 agg_strategy persistence work).
    // Previous positional rename + force-numeric coercion were band-aids that caused
    // PROD-2 (评价门店 = 4955 亿) and PROD-3 (missing 平均口味分 card).
    const kpiSummary = summaryRes.success
      ? { rowCount: summaryRes.rowCount, columnCount: summaryRes.columnCount, columns: summaryRes.columns }
      : undefined;

    if (import.meta.env.DEV) console.table(timings);

    const hasContent = !!(charts.length || aiAnalysis);
    const enrichResult: EnrichResult = {
      success: hasContent,
      charts: charts.length ? charts : undefined,
      kpiSummary,
      financialMetrics,
      aiAnalysis: aiAnalysis || undefined,
      structuredAI,
      chartConfig: charts.length ? charts[0].config : undefined,
      error: hasContent ? undefined : '未能生成图表或 AI 洞察',
      timings,
      rawData: cleanedData,
      displayNameMap: Object.keys(displayNameMap).length > 0 ? displayNameMap : undefined,
    };

    // Notify complete
    onProgress?.({ phase: 'complete', partial: enrichResult });

    if (hasContent) {
      saveAnalysisToCache(uploadId, getFactoryId(), enrichResult).catch(() => {});
    }

    return enrichResult;
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      return { success: false, error: '分析已取消' };
    }
    console.error('enrichSheetAnalysis 失败:', error);
    return {
      success: false,
      error: error instanceof Error ? error.message : 'Sheet 数据增强失败'
    };
  } finally {
    activeControllers.delete(`enrich-${uploadId}`);
    enrichmentLimiter.release();
  }
}

/**
 * Phase 1 (Apr 23 2026): send 👍/👎 feedback on an LLM-fallback answer.
 * Returns true on success. FE button handlers should optimistically
 * flip their state and not block on the call.
 */
export async function logFeedback(
  logId: number,
  value: 1 | -1 | 0,
  comment?: string,
): Promise<boolean> {
  const url = `${PYTHON_SMARTBI_URL}/api/smartbi/admin/fallback-log/${logId}/feedback`;
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        ...getPythonAuthHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ value, comment: comment ?? null }),
    });
    return res.ok;
  } catch (e) {
    console.warn('[logFeedback] request failed:', e);
    return false;
  }
}

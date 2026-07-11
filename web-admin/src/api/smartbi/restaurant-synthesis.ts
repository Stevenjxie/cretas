/**
 * Restaurant comprehensive-synthesis chat adapter (2026-07-11).
 *
 * The web-admin 餐饮 AI 聊天 (RestaurantChatPanel.vue) previously routed every
 * question through the Java intent executor (`askRestaurantQuestion` in
 * `./restaurant-chat.ts`), which does NOT reach the Python P2 comprehensive
 * synthesis engine — so 成本率/供应商价格异常(反回扣)/同比环比/加权毛利率
 * questions returned empty data on web-admin even though the mobile page
 * (`mobile-rest-ai/`) already answers them correctly.
 *
 * This adapter calls the SAME endpoint mobile-rest-ai/src/api.ts uses
 * (`POST /api/smartbi/synthesis/comprehensive`) directly from web-admin,
 * closing that gap. Chart normalization mirrors mobile-rest-ai's
 * `normalizeCharts` (backend emits a flat {chartType, title, xAxis, series}
 * shape — this strips the wrapper keys into a renderable ECharts `option`).
 *
 * NOTE: this does NOT touch `./restaurant-chat.ts` / `askRestaurantQuestion` —
 * that Java-intent path (RESTAURANT_OWNER_ACTION_CHAT scenario chaining,
 * sections, followUpChips) is left intact and still covered by its own tests;
 * RestaurantChatPanel.vue now calls this adapter instead for its primary Q&A.
 */
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from './common';

/** 🔒 反回扣(anti-kickback) alert — see backend
 * ComprehensiveSynthesisEngine.collect_alerts (smartbi/agent/synthesis_engine.py).
 * Derived ONLY from grounded FactBook signals; never fabricated. */
export interface SynthesisAlert {
  type: string; // 'supplier_price_anomaly' | 'cost_ratio_rising' | future types
  level: 'high' | 'medium' | string;
  title: string;
  detail: string;
}

/** Normalized chart ready for ECharts `setOption(option)`. */
export interface SynthesisChart {
  type?: string;
  title?: string;
  option: Record<string, unknown>;
}

export interface RestaurantSynthesisResult {
  success: boolean;
  answer: string;
  charts: SynthesisChart[];
  alerts: SynthesisAlert[];
  source?: string;
  tokens?: number;
  error?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function hasRenderableOption(option: Record<string, unknown>): boolean {
  return ['series', 'xAxis', 'yAxis', 'dataset', 'radar', 'calendar'].some((key) => key in option);
}

function needsValueAxis(option: Record<string, unknown>): boolean {
  if (!('xAxis' in option) || 'yAxis' in option) return false;
  const series = option.series;
  if (!Array.isArray(series)) return false;
  return series.some((item) => isRecord(item) && (item.type === 'bar' || item.type === 'line'));
}

/** Backend chart shape is flat: {chartType, title, xAxis, series, ...}.
 * Strip the non-option wrapper keys so the rest can go straight into
 * echarts.setOption() (mirrors mobile-rest-ai/src/api.ts normalizeOption). */
function normalizeOption(raw: Record<string, unknown>): Record<string, unknown> | null {
  const candidate = isRecord(raw.option)
    ? { ...(raw.option as Record<string, unknown>) }
    : Object.fromEntries(
        Object.entries(raw).filter(([key]) => !['chartType', 'title', 'type'].includes(key)),
      );
  if (needsValueAxis(candidate)) {
    candidate.yAxis = { type: 'value' };
  }
  return hasRenderableOption(candidate) ? candidate : null;
}

function normalizeCharts(rawCharts: unknown): SynthesisChart[] {
  if (!Array.isArray(rawCharts)) return [];
  return rawCharts.flatMap((rawChart) => {
    if (!isRecord(rawChart)) return [];
    const option = normalizeOption(rawChart);
    if (!option) return [];
    return [{
      type: typeof rawChart.chartType === 'string'
        ? rawChart.chartType
        : typeof rawChart.type === 'string' ? rawChart.type : undefined,
      title: typeof rawChart.title === 'string' ? rawChart.title : undefined,
      option,
    }];
  });
}

function normalizeAlerts(rawAlerts: unknown): SynthesisAlert[] {
  if (!Array.isArray(rawAlerts)) return [];
  return rawAlerts.flatMap((raw) => {
    if (!isRecord(raw)) return [];
    const title = typeof raw.title === 'string' ? raw.title : '';
    const detail = typeof raw.detail === 'string' ? raw.detail : '';
    // Honest rendering only — an alert missing its own explanation is dropped
    // rather than shown half-formed (never fabricate the missing half).
    if (!title || !detail) return [];
    return [{
      type: typeof raw.type === 'string' ? raw.type : '',
      level: typeof raw.level === 'string' ? raw.level : '',
      title,
      detail,
    }];
  });
}

/**
 * Ask the restaurant comprehensive-synthesis engine directly.
 *
 * `sessionId` enables the backend's P2 multi-turn memory (resolves "它"/"那家
 *店"/"第三点" follow-ups) — pass the same id across turns in one conversation,
 * a fresh id ({@link crypto.randomUUID}) per new conversation.
 */
export async function askRestaurantSynthesis(
  question: string,
  sessionId?: string,
): Promise<RestaurantSynthesisResult> {
  try {
    const raw = await pythonFetch<Record<string, unknown>>('/api/smartbi/synthesis/comprehensive', {
      method: 'POST',
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
      body: JSON.stringify({
        question,
        session_id: sessionId,
      }),
    });
    const answer = typeof raw.answer === 'string' ? raw.answer : '';
    return {
      success: true,
      answer,
      charts: normalizeCharts(raw.charts),
      alerts: normalizeAlerts(raw.alerts),
      source: typeof raw.source === 'string' ? raw.source : undefined,
      tokens: typeof raw.tokens === 'number' ? raw.tokens : undefined,
    };
  } catch (error) {
    return {
      success: false,
      answer: '',
      charts: [],
      alerts: [],
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

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
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS, PYTHON_SMARTBI_URL, getPythonAuthHeaders } from './common';
import { parseSseFrame, splitSseFrames } from './sse';

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

export interface RestaurantSynthesisContext {
  /** Display context is kept separate from the user question so it cannot pollute routing/date parsing. */
  pageContext?: string;
  /** Controlled routing hints; backend ignores unknown values. */
  dimensionHints?: string[];
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
  context?: RestaurantSynthesisContext,
): Promise<RestaurantSynthesisResult> {
  try {
    const body: Record<string, unknown> = {
      question,
      session_id: sessionId,
    };
    if (context?.pageContext) body.page_context = context.pageContext;
    if (context?.dimensionHints?.length) body.dimension_hints = context.dimensionHints;
    const raw = await pythonFetch<Record<string, unknown>>('/api/smartbi/synthesis/comprehensive', {
      method: 'POST',
      timeoutMs: PYTHON_LLM_TIMEOUT_MS,
      body: JSON.stringify(body),
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

export interface RestaurantSynthesisStreamCallbacks {
  /** Backend progress line (e.g. "正在读取经营数据…") — shown while no content yet. */
  onStatus?: (text: string) => void;
  /** Incremental markdown answer text — append to the current AI turn. */
  onChunk?: (text: string) => void;
  /** Charts can arrive before `done`; already normalized to ECharts options. */
  onCharts?: (charts: SynthesisChart[]) => void;
  /** Final payload — authoritative answer/charts/alerts/source. */
  onDone?: (result: RestaurantSynthesisResult) => void;
  /** Backend-signalled stream error (honest message, never a fake answer). */
  onError?: (message: string) => void;
}

/** Stream idle timeout: abort if no bytes arrive for this long. */
const STREAM_IDLE_TIMEOUT_MS = 60000;

function unwrapData(payload: unknown): unknown {
  if (isRecord(payload) && 'data' in payload) {
    return (payload as { data: unknown }).data;
  }
  return payload;
}

function parseJsonPayload(payload: string): unknown {
  if (!payload) return null;
  try {
    return JSON.parse(payload);
  } catch {
    return null;
  }
}

function normalizeStreamDone(payload: unknown): RestaurantSynthesisResult {
  const data = unwrapData(payload);
  const record = isRecord(data) ? data : {};
  return {
    success: true,
    answer: typeof record.answer === 'string' ? record.answer : '',
    charts: normalizeCharts(record.charts),
    alerts: normalizeAlerts(record.alerts),
    source: typeof record.source === 'string' ? record.source : undefined,
    tokens: typeof record.tokens === 'number' ? record.tokens : undefined,
  };
}

/**
 * Streaming variant of {@link askRestaurantSynthesis}: consumes the SSE
 * endpoint `/api/smartbi/synthesis/comprehensive-stream` (the SAME one
 * mobile-rest-ai/src/api.ts `askSynthesisStream` uses) via fetch +
 * ReadableStream, emitting status/chunk/charts/done/error callbacks.
 *
 * Auth/base-URL conventions mirror `pythonFetch`: `PYTHON_SMARTBI_URL`
 * prefix, `getPythonAuthHeaders()` (cookie + Bearer fallback),
 * `credentials: 'include'`.
 *
 * Throws on any failure (HTTP error, stream `error` event, incomplete
 * stream, idle timeout) — the caller decides whether to fall back to the
 * non-stream `askRestaurantSynthesis` (RestaurantChatPanel falls back only
 * when no chunk has arrived yet).
 */
export async function askRestaurantSynthesisStream(
  question: string,
  sessionId: string | undefined,
  callbacks: RestaurantSynthesisStreamCallbacks,
  context?: RestaurantSynthesisContext,
): Promise<void> {
  const body: Record<string, unknown> = {
    question,
    session_id: sessionId,
  };
  if (context?.pageContext) body.page_context = context.pageContext;
  if (context?.dimensionHints?.length) body.dimension_hints = context.dimensionHints;

  const controller = new AbortController();
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  let idleTimedOut = false;
  const resetIdle = () => {
    if (idleTimer !== undefined) clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      idleTimedOut = true;
      controller.abort();
    }, STREAM_IDLE_TIMEOUT_MS);
  };
  resetIdle();

  let sawDone = false;
  const handleFrame = (frame: string) => {
    const parsed = parseSseFrame(frame);
    if (!parsed) return;
    if (parsed.event === 'status') {
      callbacks.onStatus?.(parsed.data);
    } else if (parsed.event === 'chunk') {
      callbacks.onChunk?.(parsed.data);
    } else if (parsed.event === 'charts') {
      callbacks.onCharts?.(normalizeCharts(parseJsonPayload(parsed.data)));
    } else if (parsed.event === 'done') {
      sawDone = true;
      callbacks.onDone?.(normalizeStreamDone(parseJsonPayload(parsed.data)));
    } else if (parsed.event === 'error') {
      const message = parsed.data || '流式分析失败，请重试';
      callbacks.onError?.(message);
      throw new Error(message);
    }
  };

  try {
    let response: Response;
    try {
      response = await fetch(`${PYTHON_SMARTBI_URL}/api/smartbi/synthesis/comprehensive-stream`, {
        method: 'POST',
        credentials: 'include',
        signal: controller.signal,
        headers: getPythonAuthHeaders(),
        body: JSON.stringify(body),
      });
    } catch (error) {
      if (idleTimedOut) throw new Error('流式响应超时，请重试');
      throw error;
    }

    if (!response.ok) {
      throw new Error(`Python service error: ${response.status} ${response.statusText}`);
    }
    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error('当前浏览器不支持流式响应');
    }

    const decoder = new TextDecoder();
    let buffer = '';
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        resetIdle();
        buffer += decoder.decode(value, { stream: true });
        const split = splitSseFrames(buffer);
        buffer = split.rest;
        for (const frame of split.frames) handleFrame(frame);
      }
      const tail = decoder.decode();
      if (tail) buffer += tail;
      if (buffer.trim()) {
        const split = splitSseFrames(`${buffer}\n\n`);
        for (const frame of split.frames) handleFrame(frame);
      }
      if (!sawDone) {
        throw new Error('流式响应未完整结束，请重试');
      }
    } catch (error) {
      if (idleTimedOut) throw new Error('流式响应超时，请重试');
      throw error;
    } finally {
      reader.releaseLock();
    }
  } finally {
    if (idleTimer !== undefined) clearTimeout(idleTimer);
  }
}

/**
 * 👍/👎 feedback on a restaurant AI answer (飞轮断点2, 2026-07-23).
 *
 * Backend correlates by (tenant-from-JWT, trim(query)) → updates the latest
 * flywheel capture row's user_feedback, or inserts a standalone feedback row
 * when the answer came from a path with no capture (synthesis-direct). Fire
 * from button handlers optimistically; returns false on any failure so the
 * caller can revert its UI state.
 */
export async function sendRestaurantAnswerFeedback(
  query: string,
  value: 1 | -1,
  comment?: string,
): Promise<boolean> {
  try {
    await pythonFetch('/api/smartbi/restaurant/feedback', {
      method: 'POST',
      body: JSON.stringify({ query, value, comment: comment || null }),
    });
    return true;
  } catch {
    return false;
  }
}

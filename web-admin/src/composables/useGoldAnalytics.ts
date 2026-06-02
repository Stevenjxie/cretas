/**
 * WS1 Task 3 — useGoldAnalytics composable.
 *
 * Shared data loader for restaurant analytics Vue pages (WS2/WS3/WS4).
 *
 * DEFAULT = all history: when `range` is null (the default), NO date params
 * are appended to the gold endpoint URL, so the Python backend returns the
 * full history (`/gold/*` treats missing start_date/end_date as "open" — see
 * backend/python/smartbi/api/gold_reads.py `_parse_range`). This replaces the
 * old "must pick an uploaded CSV first" flow.
 *
 * Generic over endpoint names — callers pass the gold endpoint slugs they need
 * (e.g. ['finance-summary', 'daily-trend']) and read back a `data` map keyed by
 * those same slugs.
 *
 * Response shape: the `/api/smartbi/gold/*` endpoints return the payload object
 * DIRECTLY (no `{success, data, message}` envelope). `pythonFetch` (common.ts)
 * unwraps the HTTP layer — it throws on non-2xx (HTTPException) and otherwise
 * returns `transformKeys(json)` (snake→camel). So a successful call yields the
 * data object; a failure throws. We still defensively honour an explicit
 * `res.success === false` field in case a future endpoint adopts the envelope.
 */
import { ref, type Ref } from 'vue';
import { pythonFetch, PYTHON_LLM_TIMEOUT_MS } from '@/api/smartbi/common';

export interface GoldAnalyticsOpts {
  /** Gold endpoint slugs, e.g. ['finance-summary', 'daily-trend']. */
  endpoints: string[];
  factoryId: string;
  /** [startDate, endDate] (YYYY-MM-DD) or null. null (default) = all history. */
  range?: [string, string] | null;
  /** Auto-load on creation. Defaults to true. */
  autoLoad?: boolean;
}

export interface UseGoldAnalyticsReturn {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data: Ref<Record<string, any>>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  range: Ref<[string, string] | null>;
  reload: () => Promise<void>;
}

export function useGoldAnalytics(opts: GoldAnalyticsOpts): UseGoldAnalyticsReturn {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const data = ref<Record<string, any>>({});
  const loading = ref(false);
  const error = ref<string | null>(null);
  // null = all history (default).
  const range = ref<[string, string] | null>(opts.range ?? null);

  async function reload(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const results = await Promise.all(
        opts.endpoints.map(async (ep) => {
          let url = `/api/smartbi/gold/${ep}?factory_id=${opts.factoryId}`;
          // ONLY append date params when an explicit range is set. Omitting them
          // = all history (backend `_parse_range` treats missing bounds as open).
          if (range.value) {
            const [start, end] = range.value;
            url += `&start_date=${start}&end_date=${end}`;
          }
          const res = await pythonFetch(url, { timeoutMs: PYTHON_LLM_TIMEOUT_MS });
          // Defensive: honour an explicit envelope failure flag if present.
          if (res && typeof res === 'object' && res.success === false) {
            throw new Error(res.message || `${ep} 加载失败`);
          }
          return [ep, res] as const;
        }),
      );
      // Collect into data keyed by endpoint name (only on full success — a
      // rejected Promise.all skips this and leaves prior data in place).
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const next: Record<string, any> = {};
      for (const [ep, res] of results) {
        next[ep] = res;
      }
      data.value = next;
    } catch (e) {
      error.value = e instanceof Error ? e.message : '分析数据加载失败';
    } finally {
      loading.value = false;
    }
  }

  if (opts.autoLoad !== false) {
    // Fire-and-forget on creation; callers can await reload() explicitly.
    void reload();
  }

  return { data, loading, error, range, reload };
}

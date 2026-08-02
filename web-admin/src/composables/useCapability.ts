/**
 * useCapability — Phase 3 Day 7 (Sub-Project A · 数据织网).
 *
 * Consumes GET /api/smartbi/capability/{factory_id} and exposes:
 *   - available  : Set<field> | null   (null = cache empty → fail-open permissive)
 *   - hasAll(req): boolean              (true if cache empty → fail-open)
 *   - missingOf(req): string[]
 *   - cardStatus : per-card { satisfied, missing } map (computed from CARD_MANIFEST)
 *   - suggestions: backend-supplied unlock suggestions
 *
 * Critical: we call raw fetch() instead of the shared pythonFetch() — the latter
 * runs transformKeys() which RECURSIVELY snake→camel-cases every object key.
 * That would mangle template_status entries (template codes like
 * `dish_sales_top_n` are semantic identifiers, not field names) and break
 * lookup against CARD_MANIFEST.
 *
 * Spec §5.1 (数据织网/02-A-能力驱动渲染.md v1.5 lines 758-877). Module-level
 * singleton state is intentional so all consumers share one in-flight fetch
 * (S5 inflight dedup) and one cached response.
 */
import { ref, computed, type Ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  PYTHON_SMARTBI_URL,
  getPythonAuthHeaders,
} from '@/api/smartbi/common';
import {
  CARD_MANIFEST,
  type CardManifestEntry,
} from '@/capability/card-manifest';

export interface CapabilityResponse {
  factory_id: string;
  available_fields: string[];
  template_status: Record<
    string,
    { satisfied: boolean | null; missing: string[]; deferred?: boolean }
  >;
  unlock_suggestions: Array<{
    missing_field: string;
    unlocks_count: number;
    unlocks_examples: string[];
    cta: string;
  }>;
  time_coverage: string[][] | null;
}

// Silence "unused import" if CardManifestEntry isn't referenced directly here
// — it's re-exported for callers that want the type.
export type { CardManifestEntry };

// ──────────────────────────────────────────────────────────────────────────
// Module-level singleton state (shared across all useCapability() consumers)
// ──────────────────────────────────────────────────────────────────────────
const _capabilityCache = ref<CapabilityResponse | null>(null);
const _loading = ref(false);
const _error = ref<Error | null>(null);
let _inflight: Promise<CapabilityResponse | null> | null = null;

/**
 * Reset state — for tests + manual logout cleanup.
 */
export function _resetCapabilityState(): void {
  _capabilityCache.value = null;
  _loading.value = false;
  _error.value = null;
  _inflight = null;
}

export function useCapability() {
  const authStore = useAuthStore();

  /**
   * Fetch capability. Dedups concurrent calls via _inflight (spec S5).
   * Fail-open: errors → returns null, hasAll/missingOf return permissive
   * defaults (spec S7).
   */
  async function fetchCapability(force = false): Promise<CapabilityResponse | null> {
    if (_capabilityCache.value && !force) return _capabilityCache.value;
    if (_inflight) return _inflight;

    const factoryId = authStore.factoryId;
    if (!factoryId) {
      // spec S8: auth-ready guard
      console.warn('[capability] auth not ready, skipping fetch');
      return null;
    }

    _loading.value = true;
    _error.value = null;

    _inflight = (async () => {
      try {
        // Raw fetch — pythonFetch's transformKeys would mangle template_status keys
        // (snake_case template codes are semantic identifiers, not field names).
        const res = await fetch(
          `${PYTHON_SMARTBI_URL}/api/smartbi/capability/${factoryId}`,
          {
            credentials: 'include',
            headers: { ...getPythonAuthHeaders() },
          },
        );
        // 503 = 该工厂尚未开通 capability 层 (gradual rollout), 服务端明说
        // 「Capability layer not enabled for factory=X」。这是**预期状态不是故障** ——
        // 记成 console.error 会让每次进驾驶舱都报红, 掩盖真正的错误
        // (2026-08-02 走查时就是被它误导过一次)。按未启用静默处理, 仍然 fail-open。
        if (res.status === 503) {
          console.info('[capability] 该工厂尚未开通 capability 层 (灰度中), 按宽松模式处理');
          return null;
        }
        if (!res.ok) throw new Error(`capability fetch failed: ${res.status}`);
        const data = (await res.json()) as CapabilityResponse;
        _capabilityCache.value = data;
        return data;
      } catch (e) {
        _error.value = e as Error;
        // spec S7 fail-open: log + null, hasAll falls back to permissive
        console.error('[capability] fetch failed, falling back to permissive mode:', e);
        return null;
      } finally {
        _loading.value = false;
        _inflight = null;
      }
    })();

    return _inflight;
  }

  /**
   * spec S6/S7 fail-open semantics:
   * - cache populated → use real data
   * - cache empty (loading or error) → return null (caller treats as
   *   "everything available")
   */
  const available = computed<Set<string> | null>(() => {
    if (!_capabilityCache.value) return null;
    return new Set(_capabilityCache.value.available_fields);
  });

  function hasAll(requires: string[]): boolean {
    if (available.value === null) return true; // fail-open
    return requires.every((f) => available.value!.has(f));
  }

  function missingOf(requires: string[]): string[] {
    if (available.value === null) return [];
    return requires.filter((f) => !available.value!.has(f));
  }

  /**
   * spec §2.2.3: card_status computed FE-side from CARD_MANIFEST.
   */
  const cardStatus = computed<Record<string, { satisfied: boolean; missing: string[] }>>(() => {
    const status: Record<string, { satisfied: boolean; missing: string[] }> = {};
    for (const card of CARD_MANIFEST) {
      status[card.id] = {
        satisfied: hasAll(card.requires),
        missing: missingOf(card.requires),
      };
    }
    return status;
  });

  return {
    fetchCapability,
    available,
    hasAll,
    missingOf,
    cardStatus,
    loading: _loading as Ref<boolean>,
    error: _error as Ref<Error | null>,
    suggestions: computed(() => _capabilityCache.value?.unlock_suggestions ?? []),
  };
}

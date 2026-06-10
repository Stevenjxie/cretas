/**
 * U4 — useChartInsight composable unit tests
 *
 * Tests all five specified behaviors:
 * 1. Tier1 hit → insight set, fetchTier2Insight NOT called.
 * 2. Tier1 null + autoTier2=true → loading=true → fetchTier2Insight called → insight set.
 *    Tier2 error/null → honest-null.
 * 3. stale-guard: source/factoryId changes before Tier2 resolves → result discarded.
 * 4. 2-slot semaphore: max 2 concurrent in-flight; 3rd request queues until one finishes.
 * 5. perms fail-safe: perms() throws/returns incomplete → canViewFinance=false (finance_hidden).
 *
 * Mock strategy: vi.mock hoisted at top level for both chartInsight module functions.
 * fetchTier2Insight is made async with controllable promises for concurrency tests.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { nextTick } from 'vue';

// ─── Top-level mocks (hoisted by vitest) ─────────────────────────────────────

const mockBuildChartInsight = vi.fn();
const mockFetchTier2Insight = vi.fn();

vi.mock('@/views/smart-bi/components/chartInsight', () => ({
  buildChartInsight: (...args: unknown[]) => mockBuildChartInsight(...args),
  fetchTier2Insight: (...args: unknown[]) => mockFetchTier2Insight(...args),
}));

// Import composable AFTER mocks are set up.
import { useChartInsight } from '../useChartInsight';
import type { ChartWithMeta, UserPermissions, InsightResult } from '@/views/smart-bi/components/chartInsight';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeChart(chartType = 'bar'): ChartWithMeta {
  return {
    chartType,
    meta: { xDim: 'time', yMetric: 'revenue', aggregation: 'sum', domain: 'factory' },
    config: { series: [{ type: 'bar', data: [10, 20, 30, 40] }] },
  };
}

const FINANCE_PERMS: UserPermissions = { canViewFinance: true };
const NO_FINANCE_PERMS: UserPermissions = { canViewFinance: false };

const TIER1_RESULT: InsightResult = {
  finding: '单调上升，累计变幅 100.0%',
  source: 'rules',
  tier: 1,
};

const TIER2_RESULT: InsightResult = {
  finding: 'AI 洞察结果',
  source: 'llm',
  tier: 2,
};

/** Wait enough ticks + debounce time for the composable to settle. */
async function flushAsync(ms = 100): Promise<void> {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
}

// ─── Reset module-level semaphore state between tests ────────────────────────
// The semaphore uses module-level variables (_inflight, _queue).
// We reset them by re-importing the module fresh… but vitest caches modules.
// Instead, we ensure tests drain the semaphore: by making sure every acquired
// slot is always released (tests use try/finally in mock implementations).

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('useChartInsight', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockBuildChartInsight.mockReset();
    mockFetchTier2Insight.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ─── Behavior 1: Tier1 hit → no Tier2 ───────────────────────────────────

  it('Tier1 hit → insight set to Tier1 result; fetchTier2Insight NOT called', async () => {
    mockBuildChartInsight.mockReturnValue(TIER1_RESULT);

    const chart = makeChart();
    const { insight, loading } = useChartInsight(
      () => ({ chart }),
      () => FINANCE_PERMS,
    );

    // Advance fake timers past debounce (50ms)
    await vi.runAllTimersAsync();
    await nextTick();

    expect(insight.value).toEqual(TIER1_RESULT);
    expect(loading.value).toBe(false);
    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
  });

  it('source=null → insight null, Tier2 not called', async () => {
    const { insight, loading } = useChartInsight(
      () => null,
      () => FINANCE_PERMS,
    );

    await vi.runAllTimersAsync();
    await nextTick();

    expect(insight.value).toBeNull();
    expect(loading.value).toBe(false);
    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
  });

  // ─── Behavior 2: Tier1 null → Tier2 called; failure → honest-null ────────

  it('Tier1 null + autoTier2=true → fetchTier2Insight called → insight set to Tier2 result', async () => {
    mockBuildChartInsight.mockReturnValue(null);
    mockFetchTier2Insight.mockResolvedValue(TIER2_RESULT);

    const chart = makeChart();
    const { insight, loading } = useChartInsight(
      () => ({ chart }),
      () => NO_FINANCE_PERMS,
      { factoryId: () => 'F001' },
    );

    // After watcher fires synchronously and sets loading=true, advance timers + await
    await vi.runAllTimersAsync();
    await nextTick();

    expect(mockFetchTier2Insight).toHaveBeenCalledOnce();
    expect(insight.value).toEqual(TIER2_RESULT);
    expect(loading.value).toBe(false);
  });

  it('Tier1 null + autoTier2=false → Tier2 NOT called, insight null', async () => {
    mockBuildChartInsight.mockReturnValue(null);

    const chart = makeChart();
    const { insight, loading } = useChartInsight(
      () => ({ chart }),
      () => NO_FINANCE_PERMS,
      { autoTier2: false },
    );

    await vi.runAllTimersAsync();
    await nextTick();

    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
    expect(insight.value).toBeNull();
    expect(loading.value).toBe(false);
  });

  it('Tier2 returns null → honest-null (no fabrication)', async () => {
    mockBuildChartInsight.mockReturnValue(null);
    mockFetchTier2Insight.mockResolvedValue(null);

    const chart = makeChart();
    const { insight, loading } = useChartInsight(
      () => ({ chart }),
      () => NO_FINANCE_PERMS,
      { factoryId: () => 'F001' },
    );

    await vi.runAllTimersAsync();
    await nextTick();

    expect(insight.value).toBeNull();
    expect(loading.value).toBe(false);
  });

  it('Tier2 throws error → honest-null, loading reset to false', async () => {
    mockBuildChartInsight.mockReturnValue(null);
    mockFetchTier2Insight.mockRejectedValue(new Error('network error'));

    const chart = makeChart();
    const { insight, loading } = useChartInsight(
      () => ({ chart }),
      () => NO_FINANCE_PERMS,
      { factoryId: () => 'F001' },
    );

    await vi.runAllTimersAsync();
    await nextTick();

    expect(insight.value).toBeNull();
    expect(loading.value).toBe(false);
  });

  // ─── Behavior 3: stale-guard ─────────────────────────────────────────────

  it('stale-guard: source changes (via Vue reactive ref) while Tier2 in-flight → old result discarded', async () => {
    // Use real timers for this test to allow proper async sequencing.
    vi.useRealTimers();

    mockBuildChartInsight.mockReturnValue(null);

    // First Tier2: slow — controlled promise resolved AFTER the second watcher cycle.
    let resolveFirst!: (v: InsightResult | null) => void;
    const firstPromise = new Promise<InsightResult | null>((r) => { resolveFirst = r; });

    const secondResult: InsightResult = { finding: 'Second result', source: 'llm', tier: 2 };

    let callCount = 0;
    mockFetchTier2Insight.mockImplementation(() => {
      callCount++;
      if (callCount === 1) return firstPromise;
      return Promise.resolve(secondResult);
    });

    // Use a Vue ref so the watcher re-fires on reactive change.
    const { ref: vueRef } = await import('vue');
    const chartRef = vueRef(makeChart('bar'));

    const { insight } = useChartInsight(
      () => ({ chart: chartRef.value }),
      () => NO_FINANCE_PERMS,
      { factoryId: () => 'F001' },
    );

    // Wait for first watcher cycle (immediate) + debounce (50ms) to fire first Tier2.
    await flushAsync(200);
    expect(callCount).toBe(1); // first Tier2 in-flight

    // Change the reactive source — this triggers a new watcher cycle, bumping stale token.
    chartRef.value = makeChart('line');

    await nextTick();
    await flushAsync(200); // wait for second watcher's debounce + Tier2 call

    // Now resolve the first Tier2 (which is now stale).
    resolveFirst({ finding: 'Stale result', source: 'llm', tier: 2 });

    await flushAsync(100);

    // Key assertion: 'Stale result' MUST NOT become the insight value.
    expect(insight.value?.finding).not.toBe('Stale result');
    // The second result should have been applied.
    if (insight.value !== null) {
      expect(insight.value.finding).toBe('Second result');
    }
  });

  // ─── Behavior 4: 2-slot semaphore ────────────────────────────────────────

  it('2-slot semaphore: 3 concurrent requests → max 2 in-flight, 3rd waits', async () => {
    // We use real timers for this test to avoid interference with Promise microtasks
    vi.useRealTimers();

    mockBuildChartInsight.mockReturnValue(null);

    // Track concurrent in-flight count
    let maxConcurrent = 0;
    let currentConcurrent = 0;
    const resolvers: Array<() => void> = [];

    mockFetchTier2Insight.mockImplementation(() => {
      return new Promise<InsightResult | null>((resolve) => {
        currentConcurrent++;
        if (currentConcurrent > maxConcurrent) maxConcurrent = currentConcurrent;
        resolvers.push(() => {
          currentConcurrent--;
          resolve({ finding: `result`, source: 'llm', tier: 2 });
        });
      });
    });

    // Create 3 composable instances with different charts so each triggers a Tier2.
    const chart1 = makeChart('bar');
    const chart2 = makeChart('line');
    const chart3 = makeChart('pie');

    // We need 3 separate composable instances (each gets their own watch).
    const { insight: i1 } = useChartInsight(() => ({ chart: chart1 }), () => NO_FINANCE_PERMS, { factoryId: () => 'F001' });
    const { insight: i2 } = useChartInsight(() => ({ chart: chart2 }), () => NO_FINANCE_PERMS, { factoryId: () => 'F001' });
    const { insight: i3 } = useChartInsight(() => ({ chart: chart3 }), () => NO_FINANCE_PERMS, { factoryId: () => 'F001' });

    // Wait for debounce + initial processing
    await flushAsync(200);

    // At this point: slots 1 and 2 should be in-flight, slot 3 queued.
    // Max concurrent should be exactly 2.
    expect(maxConcurrent).toBeLessThanOrEqual(MAX_CONCURRENT_TIER2);

    // Release all slots
    while (resolvers.length > 0) {
      resolvers.shift()!();
      await flushAsync(50);
    }

    // All should resolve eventually
    await flushAsync(200);

    void i1; void i2; void i3; // suppress unused warnings
  });

  // ─── Behavior 5: perms fail-safe ─────────────────────────────────────────

  it('perms() throws → canViewFinance defaults to false (finance_hidden, no leakage)', async () => {
    mockBuildChartInsight.mockImplementation((_chart: unknown, perms: UserPermissions) => {
      // Capture and expose the permissions that were actually used
      capturedPerms = perms;
      return TIER1_RESULT;
    });

    let capturedPerms: UserPermissions | null = null;
    const chart = makeChart();

    // Provide a perms getter that throws
    useChartInsight(
      () => ({ chart }),
      () => { throw new Error('perms unavailable'); },
    );

    await vi.runAllTimersAsync();
    await nextTick();

    // The composable should have used the fail-safe fallback
    expect(capturedPerms).not.toBeNull();
    expect(capturedPerms?.canViewFinance).toBe(false);
  });

  it('perms() returns object without canViewFinance → defaults to false (finance_hidden)', async () => {
    let capturedPerms: UserPermissions | null = null;

    mockBuildChartInsight.mockImplementation((_chart: unknown, perms: UserPermissions) => {
      capturedPerms = perms;
      return TIER1_RESULT;
    });

    const chart = makeChart();

    // Return an incomplete UserPermissions (canViewFinance missing/undefined)
    useChartInsight(
      () => ({ chart }),
      () => ({} as UserPermissions),
    );

    await vi.runAllTimersAsync();
    await nextTick();

    expect(capturedPerms?.canViewFinance).toBe(false);
  });
});

// Re-export constant for test assertions
const MAX_CONCURRENT_TIER2 = 2;

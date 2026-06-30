/**
 * ChartInsightProvider.vue — Phase B keystone test suite.
 *
 * Four test contracts:
 *
 * 1. Tier1-coverable chart → renders .chart-insight-finding (Tier1 fires synchronously
 *    in the composable's watch; no Tier2 needed when buildChartInsight returns non-null).
 *
 * 2. N=3 instances in a v-for each renders its own insight independently (no cross-talk).
 *    This is the core v-for-safety proof: each component instance owns its own
 *    useChartInsight call and separate reactive state.
 *
 * 3. null chart → renders nothing, no crash (honest-empty per "禁止降级处理").
 *
 * 4. Changing the `chart` prop re-fires the insight (reactive source getter).
 *
 * Mock strategy: vi.mock hoisted at top level (same pattern as useChartInsight.spec.ts).
 * Both buildChartInsight and fetchTier2Insight are mocked; Tier2 is effectively
 * disabled (buildChartInsight returns non-null so Tier2 never fires, keeping tests fast).
 *
 * Rendering: @vue/test-utils mount with global stubs for ChartInsight's el-* internals.
 * ChartInsight.vue itself is NOT stubbed so we can assert on its rendered DOM
 * (specifically .chart-insight-finding), which validates the full integration.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick, ref } from 'vue';
import type { InsightResult, ChartWithMeta, UserPermissions } from '../chartInsight';

// ─── Top-level mocks (hoisted by vitest) ──────────────────────────────────────

const mockBuildChartInsight = vi.fn();
const mockFetchTier2Insight = vi.fn();

vi.mock('@/views/smart-bi/components/chartInsight', () => ({
  buildChartInsight: (...args: unknown[]) => mockBuildChartInsight(...args),
  fetchTier2Insight: (...args: unknown[]) => mockFetchTier2Insight(...args),
  // Re-export types (values not needed but keeps import side-effect free)
}));

// Import component AFTER mocks
import ChartInsightProvider from '../ChartInsightProvider.vue';

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** Stubs for Element Plus components used by ChartInsight.vue */
const elStubs = {
  'el-icon': { template: '<i><slot /></i>' },
};

/** A RANKING-family chart: xDim=store yields instant Tier1 from buildRankingInsight */
function makeRankingChart(): ChartWithMeta {
  return {
    chartType: 'bar',
    meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
    config: {
      xAxis: { data: ['门店A', '门店B', '门店C'] },
      series: [{ type: 'bar', data: [300, 200, 100] }],
    },
  };
}

const PERMS: UserPermissions = { canViewFinance: true };
const NO_FINANCE_PERMS: UserPermissions = { canViewFinance: false };

const TIER1_FINDING = '门店A 排名最高，是末位 门店C 的 3.0倍；占合计 50.0%';

const TIER1_RESULT: InsightResult = {
  finding: TIER1_FINDING,
  implication: '头部集中度高。',
  suggestion: '关注差距原因。',
  source: 'rules',
  tier: 1,
};

/** Flush the composable's 50ms debounce + all async tasks */
async function settle(ms = 80): Promise<void> {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await flushPromises();
  await nextTick();
}

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('ChartInsightProvider', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockBuildChartInsight.mockReset();
    mockFetchTier2Insight.mockReset();
    // Default: Tier1 returns a result (Tier2 never fires)
    mockBuildChartInsight.mockReturnValue(TIER1_RESULT);
    // Tier2 mock in case it fires: return null (honest-empty)
    mockFetchTier2Insight.mockResolvedValue(null);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ── Test 1: Tier1 hit → .chart-insight-finding rendered ───────────────────

  it('RANKING chart (Tier1 hit) → renders .chart-insight-finding with the insight text', async () => {
    const wrapper = mount(ChartInsightProvider, {
      props: {
        chart: makeRankingChart(),
        perms: PERMS,
        factoryId: 'F001',
        autoTier2: true,
        depth: 'concise',
      },
      global: { stubs: elStubs },
    });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    // ChartInsight renders .chart-insight-finding when insight is non-null
    const finding = wrapper.find('.chart-insight-finding');
    expect(finding.exists()).toBe(true);
    expect(finding.text()).toBe(TIER1_FINDING);

    // Tier2 must NOT have been called (Tier1 hit)
    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
  });

  // ── Test 2: N=3 instances in a v-for are independent (no cross-talk) ───────

  it('N=3 instances each render their own independent insight (v-for safety proof)', async () => {
    // Set up 3 different insight results so we can verify each instance has its own
    const findings = [
      '门店X 排名最高，是末位 门店Z 的 5.0倍；占合计 60.0%',
      '产品Y 排名最高，是末位 产品C 的 2.5倍；占合计 45.0%',
      '渠道堂食 排名最高，是末位 渠道外卖 的 1.5倍；占合计 55.0%',
    ];

    const charts: ChartWithMeta[] = [
      {
        chartType: 'bar',
        meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
        config: {
          xAxis: { data: ['门店X', '门店Y', '门店Z'] },
          series: [{ type: 'bar', data: [500, 200, 100] }],
        },
      },
      {
        chartType: 'bar',
        meta: { xDim: 'product', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
        config: {
          xAxis: { data: ['产品A', '产品B', '产品C'] },
          series: [{ type: 'bar', data: [250, 150, 100] }],
        },
      },
      {
        chartType: 'bar',
        meta: { xDim: 'channel', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
        config: {
          xAxis: { data: ['堂食', '外卖', '自取'] },
          series: [{ type: 'bar', data: [550, 300, 150] }],
        },
      },
    ];

    // Each call to buildChartInsight returns the finding corresponding to that chart's index.
    // We use a call counter to route findings.
    let callIdx = 0;
    mockBuildChartInsight.mockImplementation(() => {
      const idx = callIdx++;
      return {
        finding: findings[idx % findings.length],
        source: 'rules',
        tier: 1,
      } as InsightResult;
    });

    // Mount parent component that renders 3 ChartInsightProvider instances
    const Parent = {
      components: { ChartInsightProvider },
      template: `
        <div>
          <ChartInsightProvider
            v-for="(c, i) in charts"
            :key="i"
            :chart="c"
            :perms="perms"
            factory-id="F001"
          />
        </div>
      `,
      setup() {
        return { charts, perms: PERMS };
      },
    };

    const wrapper = mount(Parent, {
      global: { stubs: elStubs },
    });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    // Each instance should have rendered its own .chart-insight-finding
    const allFindings = wrapper.findAll('.chart-insight-finding');
    expect(allFindings).toHaveLength(3);

    // Each finding must match the correct per-instance text (no cross-talk)
    for (let i = 0; i < 3; i++) {
      expect(allFindings[i].text()).toBe(findings[i]);
    }

    // Tier2 must never have been called (Tier1 hit for all 3)
    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
  });

  // ── Test 3: null chart → renders nothing, no crash ────────────────────────

  it('null chart → renders nothing (honest-empty), no crash, Tier2 not called', async () => {
    const wrapper = mount(ChartInsightProvider, {
      props: {
        chart: null,
        perms: PERMS,
      },
      global: { stubs: elStubs },
    });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    // null chart → source getter returns null → watcher resets insight to null
    // ChartInsight renders nothing when insight=null and loading=false
    expect(wrapper.find('.chart-insight-finding').exists()).toBe(false);
    expect(wrapper.find('.chart-insight-loading').exists()).toBe(false);
    expect(wrapper.find('.chart-insight-container').exists()).toBe(false);

    expect(mockBuildChartInsight).not.toHaveBeenCalled();
    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
  });

  // ── Test 4: changing chart prop re-fires insight ───────────────────────────

  it('changing the chart prop triggers a new insight evaluation', async () => {
    const chartA: ChartWithMeta = {
      chartType: 'bar',
      meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: ['门店A', '门店B'] },
        series: [{ type: 'bar', data: [300, 100] }],
      },
    };

    const chartB: ChartWithMeta = {
      chartType: 'bar',
      meta: { xDim: 'product', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
      config: {
        xAxis: { data: ['产品X', '产品Y'] },
        series: [{ type: 'bar', data: [500, 100] }],
      },
    };

    const findingA = '门店A 是最高';
    const findingB = '产品X 是最高';

    // First call returns findingA, second call returns findingB
    mockBuildChartInsight
      .mockReturnValueOnce({ finding: findingA, source: 'rules', tier: 1 } as InsightResult)
      .mockReturnValueOnce({ finding: findingB, source: 'rules', tier: 1 } as InsightResult);

    const wrapper = mount(ChartInsightProvider, {
      props: {
        chart: chartA,
        perms: NO_FINANCE_PERMS,
      },
      global: { stubs: elStubs },
    });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    // Initial state: findingA
    expect(wrapper.find('.chart-insight-finding').text()).toBe(findingA);
    expect(mockBuildChartInsight).toHaveBeenCalledOnce();

    // Change the chart prop to chartB
    await wrapper.setProps({ chart: chartB });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    // After prop change: findingB
    expect(wrapper.find('.chart-insight-finding').text()).toBe(findingB);
    expect(mockBuildChartInsight).toHaveBeenCalledTimes(2);

    // Tier2 never fired (both Tier1 hits)
    expect(mockFetchTier2Insight).not.toHaveBeenCalled();
  });

  it('localizes public-review product charts as dish reputation, not generic projects', async () => {
    const chart: ChartWithMeta = {
      title: '\u5927\u4f17\u70b9\u8bc4\u83dc\u54c1\u53e3\u7891',
      chartType: 'bar',
      meta: { xDim: 'product', yMetric: 'review_count', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: ['A', 'B', 'C'] },
        series: [{ type: 'bar', data: [300, 200, 100] }],
      },
    };
    mockBuildChartInsight.mockReturnValue({
      finding: '\u9879\u76ee3\u6392\u7b2c\u4e00\uff0c\u5360\u6574\u4f5325.0%\uff1b\u672b\u4f4d\u662f\u9879\u76ee1',
      implication: '\u5c11\u6570\u95e8\u5e97\u6216\u5c11\u6570\u83dc\u54c1\u62c9\u5f00\u5dee\u8ddd\u3002',
      suggestion: '\u56f4\u7ed5\u4f9b\u7ed9\u3001\u4ef7\u683c\u3001\u66dd\u5149\u548c\u6267\u884c\u590d\u76d8\u3002',
      source: 'rules',
      tier: 1,
    } as InsightResult);

    const wrapper = mount(ChartInsightProvider, {
      props: { chart, perms: NO_FINANCE_PERMS, depth: 'detailed' },
      global: { stubs: elStubs },
    });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('\u83dc\u54c1\u53e3\u78913');
    expect(text).toContain('\u8bc4\u5206\u3001\u4f4e\u661f\u5360\u6bd4\u3001\u56de\u590d\u548c\u670d\u52a1\u4f53\u9a8c');
    expect(text).not.toContain('\u9879\u76ee3');
    expect(text).not.toContain('\u5c11\u6570\u95e8\u5e97\u6216\u5c11\u6570\u83dc\u54c1');
  });

  it('does not double-localize restaurant project labels', async () => {
    const chart: ChartWithMeta = {
      title: '\u9910\u996e\u7ecf\u8425\u5206\u6790',
      chartType: 'bar',
      meta: { xDim: 'store', yMetric: 'revenue', aggregation: 'sum', domain: 'restaurant' },
      config: {
        xAxis: { data: ['A', 'B', 'C'] },
        series: [{ type: 'bar', data: [300, 200, 100] }],
      },
    };
    mockBuildChartInsight.mockReturnValue({
      finding: '\u9910\u996e\u9879\u76ee3\u6392\u7b2c\u4e00',
      source: 'rules',
      tier: 1,
    } as InsightResult);

    const wrapper = mount(ChartInsightProvider, {
      props: { chart, perms: NO_FINANCE_PERMS },
      global: { stubs: elStubs },
    });

    await vi.runAllTimersAsync();
    await nextTick();
    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('\u9910\u996e\u9879\u76ee3');
    expect(text).not.toContain('\u9910\u996e\u9910\u996e\u9879\u76ee3');
  });
});

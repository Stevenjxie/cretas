/**
 * WS2 #6 — chip 跳转 AI 问答自动读 route.query.q 并提交 (2026-06-02).
 *
 * Covers:
 *   - Mount with route.query.q set → inputQuery 被填充 + handleSendMessage 自动
 *     提交 (executeIntent 被调用一次, query = q). (onMounted 路径)
 *   - 已挂载时 route.query.q 变化 (经营驾驶舱 chip @click → router.push 同路由
 *     name 仅 query 变, 组件复用不重挂) → watch 触发, 再次自动提交. (watch 路径,
 *     修 "chip 卡住不跳" #6)
 *   - 首次挂载不会因 watch 重复提交 (immediate:false → 只 onMounted 提交一次).
 *
 * Heavy deps mocked — mirrors AIQuery.depth.spec.ts. useRoute 用一个可变的
 * reactive query 对象, 以便测试在 mount 后改 q 模拟 router.push。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { createPinia, setActivePinia } from 'pinia';

// ── mutable route query so we can simulate router.push(同路由 query 变) ──
const routeQuery = reactive<{ q?: string }>({});

// ── executeIntent / intent-chat ──────────────────────────────
const executeIntentMock = vi.fn();
const fetchCachedXlsxMock = vi.fn();
const submitIntentFeedbackMock = vi.fn(async () => true);
vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...args: unknown[]) => executeIntentMock(...args),
  fetchCachedXlsx: (...args: unknown[]) => fetchCachedXlsxMock(...args),
  submitIntentFeedback: (...args: unknown[]) => submitIntentFeedbackMock(...args),
}));

// ── smartbi API (Python fallback path — should NOT be called here) ──
const chatAnalysisMock = vi.fn(async () => ({ summary: '', charts: [] }));
const chatAnalysisStreamMock = vi.fn(() => ({ abort: vi.fn() }));
vi.mock('@/api/smartbi', () => ({
  chatAnalysis: (...a: unknown[]) => chatAnalysisMock(...a),
  chatAnalysisStream: (...a: unknown[]) => chatAnalysisStreamMock(...a),
  getUploadHistory: vi.fn(async () => []),
  deduplicateUploads: vi.fn(async () => ({})),
  nl2sql: vi.fn(async () => ({})),
  logFeedback: vi.fn(async () => ({})),
}));

// ── auth store + router (useRoute returns the mutable query) ──
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'RES_3101_009' }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeQuery }),
  useRouter: () => ({ push: vi.fn() }),
}));

// ── echarts + composables ────────────────────────────────────
vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) },
}));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (o: unknown) => o }));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('marked', () => ({ marked: (s: string) => s }));
vi.mock('dompurify', () => ({ default: { sanitize: (s: string) => s } }));

// ── child components → trivial stubs ─────────────────────────
vi.mock('@/components/smartbi', () => ({
  AIInsightPanel: { template: '<div />' },
}));
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('@/components/smart-bi/MaterializedAnalysisPanel.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('@/api/smartbi/materialized', () => ({
  listFactoryTemplates: vi.fn(async () => []),
}));

import AIQuery from '../AIQuery.vue';

const globalStubs = {
  'el-button': {
    props: ['icon'],
    emits: ['click'],
    template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-link': { emits: ['click'], template: '<a class="el-link" @click="$emit(\'click\')"><slot /></a>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': { template: '<input />' },
  'el-autocomplete': { template: '<div class="el-autocomplete"><slot :item="{ value: \'\' }" /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-switch': { template: '<input type="checkbox" />' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /></div>' },
};

function answer(message: string) {
  return { status: 'SUCCESS', sessionId: 's1', message, resultData: { message } };
}

describe('AIQuery WS2 #6 — chip 跳转自动读 q 并提交', () => {
  // track mounted wrappers so each test fully unmounts its component — otherwise
  // a prior test's still-alive route.query.q watcher fires on the shared reactive
  // routeQuery and inflates executeIntent call counts (test isolation, not a bug).
  let wrappers: Array<{ unmount: () => void }> = [];
  function track<T extends { unmount: () => void }>(w: T): T {
    wrappers.push(w);
    return w;
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    executeIntentMock.mockReset();
    chatAnalysisMock.mockClear();
    // reset mutable route query between tests (delete → undefined, watch ignores)
    delete routeQuery.q;
  });

  afterEach(() => {
    wrappers.forEach((w) => w.unmount());
    wrappers = [];
  });

  it('mount with route.query.q → inputQuery filled + handleSendMessage auto-submits', async () => {
    vi.useFakeTimers();
    routeQuery.q = '畅销品 Top 5';
    executeIntentMock.mockResolvedValue(answer('畅销品...'));

    track(mount(AIQuery, { global: { stubs: globalStubs } }));
    await flushPromises();
    // onMounted submits via nextTick + setTimeout(300) → advance timers.
    await vi.runAllTimersAsync();
    await flushPromises();
    vi.useRealTimers();

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][1]).toBe('畅销品 Top 5');
  });

  it('watch fires when route.query.q changes on an ALREADY-mounted AIQuery (chip click)', async () => {
    // First mount with NO q (so onMounted does not submit).
    executeIntentMock.mockResolvedValue(answer('结果...'));
    track(mount(AIQuery, { global: { stubs: globalStubs } }));
    await flushPromises();
    expect(executeIntentMock).not.toHaveBeenCalled();

    // Simulate chip @click → router.push({name:'SmartBIQuery', query:{q}}) on the
    // same already-mounted route: only the query changes, component is reused.
    routeQuery.q = '哪家店业绩最好';
    await flushPromises(); // let the watch callback run (immediate submit, no timer)
    await flushPromises();

    expect(executeIntentMock).toHaveBeenCalledTimes(1);
    expect(executeIntentMock.mock.calls[0][1]).toBe('哪家店业绩最好');
  });

  it('does NOT double-submit on first mount (watch imm:false → only onMounted submits)', async () => {
    vi.useFakeTimers();
    routeQuery.q = '总营业额';
    executeIntentMock.mockResolvedValue(answer('营业额...'));
    track(mount(AIQuery, { global: { stubs: globalStubs } }));
    await flushPromises();
    await vi.runAllTimersAsync();
    await flushPromises();
    vi.useRealTimers();

    // exactly one submit even though both onMounted and the watch see the same q
    expect(executeIntentMock).toHaveBeenCalledTimes(1);
  });
});

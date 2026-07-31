/**
 * Tests for cell-audit.vue (Sub-Project C Day 26).
 *
 * Verifies the lineage detail page renders:
 *   - Current authoritative value with confidence tag + source label
 *   - Sentinel rows hide fileName and show friendly source label
 *   - Backfill tag for isBackfill=true rows
 *   - 已替换 tag for rows with supersededById
 *   - Empty state when API returns no rows
 *
 * Mocks the python service via @/api/smartbi/common.pythonFetch.
 * Stubs Element Plus components in the same shape GoldPreview.spec.ts uses
 * (provide/inject for el-table-column rows).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// ── Mock auth store (mutable for negative-path tests) ────────────────
const mockAuth: { factoryId: string | null } = { factoryId: 'F001' };
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => mockAuth,
}));

// ── Mock vue-router (query is mutable for negative-path tests) ───────
const backSpy = vi.fn();
const mockQuery: Record<string, string | undefined> = {
  type: 'product',
  id: '42',
  field: 'cost_per_unit',
};
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: mockQuery }),
  useRouter: () => ({ back: backSpy }),
}));

// ── Mock Element Plus ElMessage (P2-10: spy is shared, reset per test)─
// vi.hoisted() lifts the spy initialization to before vi.mock evaluates,
// so the factory's lexical closure captures an already-initialized fn
// instead of hitting the TDZ at import time.
const { elMessageError } = vi.hoisted(() => ({ elMessageError: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: { error: elMessageError, success: vi.fn(), warning: vi.fn() },
}));

// ── Mock pythonFetch (the only API call this view makes) ─────────────
const mockPythonFetch = vi.fn();
vi.mock('@/api/smartbi/common', () => ({
  pythonFetch: (...args: unknown[]) => mockPythonFetch(...args),
}));

// ── Element Plus stubs (mirror GoldPreview.spec.ts) ──────────────────
const globalStubs = {
  'el-page-header': {
    props: ['content'],
    template: '<div class="el-page-header"><slot /><span class="page-content">{{ content }}</span></div>',
  },
  'el-skeleton': { template: '<div class="el-skeleton" />' },
  'el-alert': {
    props: ['type', 'title'],
    template: '<div class="el-alert" :class="`alert-${type}`">{{ title }}</div>',
  },
  'el-card': {
    template: '<div class="el-card"><div class="el-card__header"><slot name="header" /></div><div class="el-card__body"><slot /></div></div>',
  },
  'el-tag': {
    props: ['type', 'size', 'effect'],
    template: '<span class="el-tag" :class="`type-${type}`"><slot /></span>',
  },
  'el-empty': {
    props: ['description'],
    template: '<div class="el-empty">{{ description }}</div>',
  },
  'el-descriptions': { template: '<div class="el-descriptions"><slot /></div>' },
  'el-descriptions-item': {
    props: ['label'],
    template: '<div class="el-descriptions-item"><span class="dl">{{ label }}</span><span class="dv"><slot /></span></div>',
  },
  // Same provide/inject dance as GoldPreview.spec.ts so el-table-column
  // default slots see their row.
  'el-table': {
    props: ['data'],
    template: `
      <table class="el-table">
        <tbody>
          <fake-row v-for="(row, idx) in (data || [])" :key="idx" :row="row">
            <slot />
          </fake-row>
        </tbody>
      </table>
    `,
    components: {
      'fake-row': {
        props: ['row'],
        provide() {
          return {
            currentRow: () => (this as unknown as { row: unknown }).row,
          };
        },
        template: '<tr class="el-table__row"><slot /></tr>',
      },
    },
  },
  'el-table-column': {
    props: ['label', 'prop'],
    inject: {
      currentRow: { default: () => () => ({}) },
    },
    computed: {
      rowSlot(): Record<string, unknown> {
        return (this as unknown as { currentRow: () => Record<string, unknown> }).currentRow();
      },
    },
    template: '<td class="el-table__cell"><slot :row="rowSlot" /></td>',
  },
};

// Import after mocks are set up.
import CellAudit from '../cell-audit.vue';

function makeRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 100,
    sourceType: 'manual',
    sourceUploadId: 0,
    fileName: null as string | null,
    confidence: 0.9,
    validFrom: '2026-04-01',
    validTo: null as string | null,
    fieldValue: 60.0,
    mapperMethod: 'rule',
    confidenceMethod: 'rule',
    createdAt: '2026-04-15T10:23:00+00:00',
    createdBy: 'admin:steve',
    supersededById: null as number | null,
    supersededReason: null as string | null,
    notes: null as string | null,
    isBackfill: false,
    ...overrides,
  };
}

describe('cell-audit.vue (Sub-Project C Day 26)', () => {
  beforeEach(() => {
    backSpy.mockClear();
    mockPythonFetch.mockReset();
    elMessageError.mockClear();
    // P2-10: reset mocks so negative-path tests don't leak into happy-path.
    mockAuth.factoryId = 'F001';
    mockQuery.type = 'product';
    mockQuery.id = '42';
    mockQuery.field = 'cost_per_unit';
  });

  it('renders current value card with confidence tag + sentinel source label', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow({ sourceType: 'manual', sourceUploadId: 0, fileName: null }),
      history: [makeRow({ sourceType: 'manual', sourceUploadId: 0, fileName: null })],
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('当前权威值');
    expect(html).toContain('置信度 0.900');
    // Sentinel: friendly label, no fileName leak.
    expect(html).toContain('客户手动确认');
    expect(html).not.toContain('_sentinel_manual');
    // Header carries the cell key.
    expect(html).toContain('字段血统: product #42');
    expect(html).toContain('cost_per_unit');
  });

  it('renders friendly source label when sourceUploadId is 0 (sentinel)', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow({
        sourceType: 'industry_default',
        sourceUploadId: 0,
        fileName: null,
      }),
      history: [
        makeRow({
          sourceType: 'industry_default',
          sourceUploadId: 0,
          fileName: null,
        }),
      ],
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('行业默认值');
    // No "(#0)" or sentinel filename should appear.
    expect(html).not.toContain('(#0)');
    expect(html).not.toContain('_sentinel_manual');
  });

  it('shows fileName (#upload_id) for non-sentinel uploads', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow({
        sourceType: 'pos_excel',
        sourceUploadId: 4180,
        fileName: 'recipe_v3_2026.xlsx',
      }),
      history: [
        makeRow({
          sourceType: 'pos_excel',
          sourceUploadId: 4180,
          fileName: 'recipe_v3_2026.xlsx',
        }),
      ],
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('recipe_v3_2026.xlsx');
    expect(html).toContain('(#4180)');
  });

  it('renders 回填 tag when isBackfill is true', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow({ isBackfill: true, createdBy: 'backfill_from_agg' }),
      history: [makeRow({ isBackfill: true, createdBy: 'backfill_from_agg' })],
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const tags = wrapper.findAll('.el-tag');
    const backfillTag = tags.find((t) => t.text() === '回填');
    expect(backfillTag).toBeDefined();
  });

  it('renders 已替换 tag for rows with supersededById set', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow({ id: 200, confidence: 0.95 }),
      history: [
        makeRow({ id: 200, confidence: 0.95 }),
        makeRow({
          id: 199,
          confidence: 0.5,
          supersededById: 200,
          supersededReason: 'higher_priority',
          validFrom: '2026-01-01',
        }),
      ],
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const tags = wrapper.findAll('.el-tag');
    const supersededTag = tags.find((t) => t.text() === '已替换');
    expect(supersededTag).toBeDefined();
    // Replacement reason should appear in the table.
    expect(wrapper.html()).toContain('higher_priority');
  });

  it('renders empty state when API returns no rows', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: null,
      history: [],
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    // Per spec — empty state Chinese label.
    expect(html).toContain('此字段暂无 provenance 记录');
    // No history rows to render.
    expect(wrapper.findAll('.el-table__row')).toHaveLength(0);
  });

  // P2-10 (post-D27 review): error-path coverage.

  it('shows missing-param error when URL is missing required query keys', async () => {
    mockQuery.field = undefined;  // remove `field` → fail param check

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('缺少必要参数');
    // No fetch should have been issued — view rejects before calling backend.
    expect(mockPythonFetch).not.toHaveBeenCalled();
  });

  it('shows factory-id error when auth store has no factoryId', async () => {
    mockAuth.factoryId = null;

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('factoryId');  // view inserts the literal token in the error
    expect(mockPythonFetch).not.toHaveBeenCalled();
  });

  it('shows error toast and alert when API call rejects (network/server error)', async () => {
    mockPythonFetch.mockRejectedValueOnce(new Error('factory_id 参数 unknown 与登录工厂 F001 不一致'));

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    // ElMessage.error toast was called with a Chinese-prefixed message.
    expect(elMessageError).toHaveBeenCalledTimes(1);
    const errArg = elMessageError.mock.calls[0][0] as string;
    expect(errArg).toContain('字段血统查询失败');
    expect(errArg).toContain('factory_id 参数');
    // Alert banner is also shown so the error is non-modal-persistent.
    expect(wrapper.find('.el-alert').exists()).toBe(true);
  });

  // P0-C1 (post-review test backfill): truncated banner.
  // Backend caps history at HISTORY_LIMIT=500 + flags ``truncated:true`` so
  // the FE shows "仅显示最近 500 条 — 完整历史请联系运维". Without this
  // test the banner Chinese text could drift unverified, or the v-if
  // could break in a refactor.

  // ⏱️ 显式 20s 超时: 本用例要挂载 500 行历史再整棵序列化 (`wrapper.html()`),
  // 单独跑约 2.0s, 而同文件其它用例都在 4~143ms —— 它本来就贴着 vitest 默认 5s 跑。
  // 整套跑起来多 worker 抢 CPU 就会越过 5s, 报一个含糊的 `Error: STACK_TRACE_ERROR`
  // (看不出是超时), 表现为「同一份代码这次绿下次红」。
  // 500 行是这个场景本身 (后端 HISTORY_LIMIT=500 截断), 不是可以随手调小的测试数据,
  // 所以给时间而不是缩数据。
  it('renders truncated banner when API reports truncated history', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow(),
      history: Array.from({ length: 500 }, (_, i) => makeRow({ id: 100 + i })),
      truncated: true,
      historyLimit: 500,
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('仅显示最近 500 条');
    expect(html).toContain('完整历史请联系运维');
  }, 20_000);

  it('does NOT render truncated banner when API truncated is false (or absent)', async () => {
    mockPythonFetch.mockResolvedValueOnce({
      current: makeRow(),
      history: [makeRow()],
      truncated: false,
      historyLimit: 500,
      key: { factoryId: 'F001', entityType: 'product', entityId: '42', field: 'cost_per_unit' },
    });

    const wrapper = mount(CellAudit, { global: { stubs: globalStubs } });
    await flushPromises();

    const html = wrapper.html();
    expect(html).not.toContain('仅显示最近 500 条');
  });
});

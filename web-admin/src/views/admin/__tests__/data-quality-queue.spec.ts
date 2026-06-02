/**
 * Tests for data-quality-queue.vue (餐饮 Phase A A-3, Task 3.5).
 *
 * Test 1: renders entity_type tabs — all 8 tab labels visible
 * Test 2: loads list when factoryId provided — items appear in table
 * Test 3: shows 4-eye gate disabled state when submitter == currentUser
 * Test 4: resolve button calls API + shows success toast
 * Test 5: batch operation submits selected IDs
 *
 * All network calls mocked. No `as any`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

// ── Mock auth store ────────────────────────────────────────────────────────
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'F001',
    currentRole: 'factory_super_admin',
    user: { id: 1, username: 'admin1' },
  }),
}));

// ── Mock API client ────────────────────────────────────────────────────────
const mockListQueue = vi.fn();
const mockResolveQueue = vi.fn();
const mockRejectQueue = vi.fn();
const mockBatchResolveQueue = vi.fn();
const mockGetAdminCount = vi.fn();

vi.mock('@/api/admin/data-quality-queue', () => ({
  listQueue: (...args: unknown[]) => mockListQueue(...args),
  resolveQueue: (...args: unknown[]) => mockResolveQueue(...args),
  rejectQueue: (...args: unknown[]) => mockRejectQueue(...args),
  batchResolveQueue: (...args: unknown[]) => mockBatchResolveQueue(...args),
  getAdminCount: (...args: unknown[]) => mockGetAdminCount(...args),
}));

// ── Mock Element Plus ──────────────────────────────────────────────────────
const { elMessageSuccess, elMessageError, elMessageWarning } = vi.hoisted(() => ({
  elMessageSuccess: vi.fn(),
  elMessageError: vi.fn(),
  elMessageWarning: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElMessage: {
    success: elMessageSuccess,
    error: elMessageError,
    warning: elMessageWarning,
  },
  ElMessageBox: {
    confirm: vi.fn().mockResolvedValue('confirm'),
  },
}));

// ── Global stubs ───────────────────────────────────────────────────────────

const globalStubs = {
  'el-card': {
    template: '<div class="el-card"><div class="el-card__body"><slot /></div></div>',
  },
  'el-skeleton': { template: '<div class="el-skeleton" />' },
  'el-empty': {
    props: ['description'],
    template: '<div class="el-empty">{{ description }}<slot /></div>',
  },
  'el-alert': {
    props: ['title', 'description', 'type', 'closable', 'showIcon'],
    template: '<div class="el-alert" :data-title="title"><span class="el-alert__title">{{ title }}</span><span>{{ description }}</span></div>',
  },
  'el-button': {
    props: ['type', 'size', 'disabled', 'loading', 'plain'],
    emits: ['click'],
    template: '<button class="el-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
  'el-input': {
    props: ['modelValue', 'placeholder', 'clearable', 'type', 'rows'],
    emits: ['update:modelValue'],
    template: '<input class="el-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  'el-select': {
    props: ['modelValue', 'placeholder', 'clearable'],
    emits: ['update:modelValue'],
    template: '<select class="el-select" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  },
  'el-option': {
    props: ['label', 'value'],
    template: '<option :value="value">{{ label }}</option>',
  },
  'el-form-item': {
    props: ['label'],
    template: '<div class="el-form-item"><label>{{ label }}</label><slot /></div>',
  },
  'el-tabs': {
    props: ['modelValue'],
    emits: ['update:modelValue', 'tab-change'],
    template: `
      <div class="el-tabs">
        <div class="el-tabs__header">
          <slot />
        </div>
      </div>`,
  },
  'el-tab-pane': {
    props: ['label', 'name'],
    template: '<div class="el-tab-pane" :data-name="name">{{ label }}</div>',
  },
  'el-tag': {
    props: ['type', 'size'],
    template: '<span class="el-tag"><slot /></span>',
  },
  'el-text': {
    props: ['type', 'size'],
    template: '<span class="el-text"><slot /></span>',
  },
  'el-radio-group': {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<div class="el-radio-group"><slot /></div>',
  },
  'el-radio': {
    props: ['value', 'disabled'],
    template: '<label class="el-radio"><slot /></label>',
  },
  'el-dialog': {
    props: ['modelValue', 'title', 'width'],
    emits: ['update:modelValue'],
    template: `
      <div v-if="modelValue" class="el-dialog">
        <div class="el-dialog__title">{{ title }}</div>
        <slot />
        <div class="el-dialog__footer"><slot name="footer" /></div>
      </div>`,
  },
  'el-descriptions': {
    props: ['column', 'border', 'size'],
    template: '<div class="el-descriptions"><slot /></div>',
  },
  'el-descriptions-item': {
    props: ['label'],
    template: '<div class="el-descriptions-item"><span class="label">{{ label }}</span><slot /></div>',
  },
  // el-table with selection support
  'el-table': {
    props: ['data'],
    emits: ['selection-change'],
    template: `
      <table class="el-table">
        <tbody>
          <fake-row v-for="(row, idx) in (data || [])" :key="idx" :row="row">
            <slot />
          </fake-row>
        </tbody>
      </table>`,
    components: {
      'fake-row': {
        props: ['row'],
        provide() {
          const self = this as unknown as { row: unknown };
          return { currentRow: () => self.row };
        },
        template: '<tr class="el-table__row"><slot /></tr>',
      },
    },
  },
  'el-table-column': {
    props: ['label', 'prop', 'width', 'minWidth', 'fixed', 'align', 'type'],
    inject: { currentRow: { default: () => () => ({}) } },
    computed: {
      rowData(): Record<string, unknown> {
        const getter = (this as unknown as { currentRow: () => Record<string, unknown> }).currentRow;
        return getter();
      },
      cellValue(): unknown {
        const propName = (this as unknown as { prop: string | undefined }).prop;
        if (!propName) return '';
        return (this as unknown as { rowData: Record<string, unknown> }).rowData[propName];
      },
    },
    template: '<td class="el-table__cell"><slot :row="rowData">{{ cellValue }}</slot></td>',
  },
};

const vLoadingStub = { mounted() {}, updated() {}, unmounted() {} };

import DataQualityQueue from '../data-quality-queue.vue';

// ── Fixtures ───────────────────────────────────────────────────────────────

function makeListResponse(overrides: Partial<{ submitter: string }> = {}) {
  return {
    items: [
      {
        id: 101,
        factoryId: 'F001',
        entityType: 'store',
        rawName: '颛桥龙湖店',
        candidateEntityId: 42,
        confidence: 0.91,
        decidedByAgent: null as string | null,
        status: 'PENDING' as const,
        priority: 'HIGH',
        sourceUploadId: 5,
        submitter: overrides.submitter ?? '999',
        adminUser: null as string | null,
        adminAt: null as string | null,
        adminAction: null as string | null,
        reasoning: null as string | null,
        extra: null as Record<string, unknown> | null,
        createdAt: '2026-04-28T10:00:00Z',
        dishReview: null,
      },
    ],
    total: 1,
    page: 1,
    pageSize: 50,
  };
}

// P4a (Task 8): dish proposal list fixture with the dishReview enrichment.
function makeDishListResponse() {
  return {
    items: [
      {
        id: 201,
        factoryId: 'F001',
        entityType: 'dish',
        rawName: '招牌青花椒鱼',
        candidateEntityId: null as number | null,
        confidence: 0.0,
        decidedByAgent: 'rule_cluster' as string | null,
        status: 'PENDING' as const,
        priority: 'HIGH',
        sourceUploadId: null as number | null,
        submitter: null as string | null,
        adminUser: null as string | null,
        adminAt: null as string | null,
        adminAction: null as string | null,
        reasoning: '规则层归一' as string | null,
        extra: { proposal_kind: 'rule_cluster' } as Record<string, unknown> | null,
        createdAt: '2026-06-02T10:00:00Z',
        dishReview: {
          proposalKind: 'rule_cluster',
          normalizedKey: '招牌青花椒鱼',
          category: '主菜',
          suggestedCanonicalName: null,
          memberCount: 2,
          members: [
            { productId: 101, name: '招牌青花椒鱼(单人份)', stores: ['大融城店', '龙湖店'], qty: 30, revenue: 1740 },
            { productId: 102, name: '青花椒味鱼', stores: ['颛桥店'], qty: 12, revenue: 720 },
          ],
        },
      },
    ],
    total: 1,
    page: 1,
    pageSize: 50,
  };
}

// ── Tests ──────────────────────────────────────────────────────────────────

describe('data-quality-queue.vue (餐饮 Phase A A-3)', () => {
  beforeEach(() => {
    mockListQueue.mockReset();
    mockResolveQueue.mockReset();
    mockRejectQueue.mockReset();
    mockBatchResolveQueue.mockReset();
    mockGetAdminCount.mockReset();
    elMessageSuccess.mockClear();
    elMessageError.mockClear();
    elMessageWarning.mockClear();

    // Default: 2 admins, list returns empty
    mockGetAdminCount.mockResolvedValue(2);
    mockListQueue.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 50 });
  });

  // ── Test 1: entity_type tabs ───────────────────────────────────────────

  it('Test 1: renders all 8 entity_type tab labels', async () => {
    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    const html = wrapper.html();
    // All 8 entity types must appear as tab labels
    const expectedLabels = ['门店', '产品', '员工', '食材', '形态识别', '跨表合并', '周期推断', '字段冲突'];
    for (const label of expectedLabels) {
      expect(html).toContain(label);
    }
  });

  // ── Test 2: loads list when factoryId set ──────────────────────────────

  it('Test 2: loads list when factoryId provided and renders items', async () => {
    mockListQueue.mockResolvedValue(makeListResponse());
    mockGetAdminCount.mockResolvedValue(2);

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    // Auth store pre-fills F001 → auto-loads on mount
    expect(mockListQueue).toHaveBeenCalledWith(
      expect.objectContaining({ factoryId: 'F001' })
    );

    const html = wrapper.html();
    expect(html).toContain('颛桥龙湖店');
  });

  // ── Test 3: 4-eye gate disabled when submitter == currentUser ──────────

  it('Test 3: shows 4-eye gate banner + disabled confirm when submitter == currentUser', async () => {
    // submitter '1' matches currentUserId '1' from mocked auth store (user.id = 1)
    mockListQueue.mockResolvedValue(makeListResponse({ submitter: '1' }));
    mockGetAdminCount.mockResolvedValue(2); // > 1 → 4-eye active

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    // Click 处理 button on the first row
    const processBtn = wrapper.findAll('button.el-button').find(
      (b) => b.text().includes('处理')
    );
    expect(processBtn).toBeDefined();
    await processBtn!.trigger('click');
    await flushPromises();

    // Dialog should be open — look for 4-eye banner
    const html = wrapper.html();
    expect(html).toContain('4-eye 原则');

    // The confirm button inside dialog footer should be disabled
    const dialogButtons = wrapper.findAll('.el-dialog button.el-button');
    const confirmBtn = dialogButtons.find((b) => b.text().includes('确认'));
    expect(confirmBtn).toBeDefined();
    expect(confirmBtn!.attributes('disabled')).toBeDefined();
  });

  // ── Test 4: resolve calls API + success toast ──────────────────────────

  it('Test 4: clicking confirm in modal calls resolveQueue + shows success toast', async () => {
    // submitter '999' ≠ currentUser '1' → 4-eye NOT blocked
    mockListQueue.mockResolvedValue(makeListResponse({ submitter: '999' }));
    mockGetAdminCount.mockResolvedValue(2);
    mockResolveQueue.mockResolvedValue({ resolved: true });

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    // Open modal
    const processBtn = wrapper.findAll('button.el-button').find(
      (b) => b.text().includes('处理')
    );
    await processBtn!.trigger('click');
    await flushPromises();

    // Set entity ID via input (to satisfy confirm validation)
    const inputs = wrapper.findAll('input.el-input');
    // Find the entity ID input in the dialog (not the factoryId input)
    const entityInput = inputs.find((inp) => {
      const placeholder = inp.attributes('placeholder') ?? '';
      return placeholder.includes('数字 ID');
    });
    if (entityInput) {
      await entityInput.setValue('42');
    }

    // Click confirm button in dialog footer
    const dialogButtons = wrapper.findAll('.el-dialog button.el-button');
    const confirmBtn = dialogButtons.find((b) => b.text().includes('确认'));
    expect(confirmBtn).toBeDefined();
    await confirmBtn!.trigger('click');
    await flushPromises();

    expect(mockResolveQueue).toHaveBeenCalledWith(
      101,
      expect.objectContaining({ action: 'confirm' })
    );
    expect(elMessageSuccess).toHaveBeenCalled();
  });

  // ── Test 5: batch operation submits selected IDs ───────────────────────

  it('Test 5: batch operation submits selected IDs to batchResolveQueue', async () => {
    mockListQueue.mockResolvedValue(makeListResponse());
    mockGetAdminCount.mockResolvedValue(2);
    mockBatchResolveQueue.mockResolvedValue({ successCount: 1, failedItems: [] });

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    // Programmatically set selectedRows via component internal state
    // (since jsdom el-table checkbox interaction is complex, we simulate
    // the selection-change event that el-table emits)
    const table = wrapper.findComponent({ name: undefined, ref: undefined });
    // Find el-table stub and emit selection-change with the row
    const items = makeListResponse().items;
    const tableEl = wrapper.find('table.el-table');
    // Trigger @selection-change via the wrapper's vm
    const vm = wrapper.vm as unknown as { selectedRows: typeof items; batchDialogVisible: boolean };
    vm.selectedRows = items; // directly set selected rows
    await wrapper.vm.$nextTick();

    // Click 批量 confirm button
    const batchBtn = wrapper.findAll('button.el-button').find(
      (b) => b.text().includes('批量')
    );
    expect(batchBtn).toBeDefined();
    await batchBtn!.trigger('click');
    await flushPromises();

    // Dialog should appear — click confirm in it
    const dialogConfirmBtns = wrapper.findAll('.el-dialog button.el-button').filter(
      (b) => b.text().includes('确认')
    );
    if (dialogConfirmBtns.length > 0) {
      await dialogConfirmBtns[dialogConfirmBtns.length - 1].trigger('click');
      await flushPromises();
    }

    expect(mockBatchResolveQueue).toHaveBeenCalledWith(
      expect.objectContaining({ ids: [101] })
    );
  });

  // ── Test 6 (P4a): 菜名归一 dish tab label present ───────────────────────

  it('Test 6: renders the 菜名归一 (dish) tab label', async () => {
    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();
    expect(wrapper.html()).toContain('菜名归一');
  });

  // ── Test 7 (P4a): dish member dishes + stores + sales rendered ──────────

  it('Test 7: dish tab shows member dish names, stores and sales sample', async () => {
    mockListQueue.mockResolvedValue(makeDishListResponse());
    mockGetAdminCount.mockResolvedValue(2);

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    // Switch to the dish tab → reload with entityType=dish
    const vm = wrapper.vm as unknown as { activeEntityType: string; onTabChange: (n: string) => void };
    vm.onTabChange('dish');
    await flushPromises();

    const html = wrapper.html();
    // member dish names + stores + sales fragments visible (Rule 2 eyeball)
    expect(html).toContain('招牌青花椒鱼(单人份)');
    expect(html).toContain('青花椒味鱼');
    expect(html).toContain('大融城店');
    expect(html).toContain('颛桥店');
    expect(html).toContain('销 30'); // formatQty(30) inside the sales label
  });

  // ── Test 8 (P4a): dish create_new sends canonicalName ───────────────────

  it('Test 8: dish create_new resolve sends action create_new + canonicalName', async () => {
    mockListQueue.mockResolvedValue(makeDishListResponse());
    mockGetAdminCount.mockResolvedValue(2);
    mockResolveQueue.mockResolvedValue({ resolved: true });

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    const vm = wrapper.vm as unknown as {
      onTabChange: (n: string) => void;
      activeEntityType: string;
    };
    vm.onTabChange('dish');
    await flushPromises();

    // Open the dish modal via 处理 button
    const processBtn = wrapper.findAll('button.el-button').find(
      (b) => b.text().includes('处理')
    );
    expect(processBtn).toBeDefined();
    await processBtn!.trigger('click');
    await flushPromises();

    // Default dish action = create_new with rawName pre-filled → submit
    const dialogButtons = wrapper.findAll('.el-dialog button.el-button');
    const confirmBtn = dialogButtons.find((b) => b.text().includes('确认'));
    expect(confirmBtn).toBeDefined();
    await confirmBtn!.trigger('click');
    await flushPromises();

    expect(mockResolveQueue).toHaveBeenCalledWith(
      201,
      expect.objectContaining({ action: 'create_new', canonicalName: '招牌青花椒鱼' })
    );
    expect(elMessageSuccess).toHaveBeenCalled();
  });

  // ── Test 9 (P4a): dish reject uses dropdown reason ──────────────────────

  it('Test 9: dish reject sends the selected dropdown reason (not free text)', async () => {
    mockListQueue.mockResolvedValue(makeDishListResponse());
    mockGetAdminCount.mockResolvedValue(2);
    mockRejectQueue.mockResolvedValue({ rejected: true });

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    const vm = wrapper.vm as unknown as {
      onTabChange: (n: string) => void;
      modalAction: 'confirm' | 'create_new' | 'reject';
      modalItem: unknown;
      modalVisible: boolean;
    };
    vm.onTabChange('dish');
    await flushPromises();

    // Open dish modal, switch action to reject (default reason = first dropdown option)
    const processBtn = wrapper.findAll('button.el-button').find(
      (b) => b.text().includes('处理')
    );
    await processBtn!.trigger('click');
    await flushPromises();
    vm.modalAction = 'reject';
    await wrapper.vm.$nextTick();

    const dialogButtons = wrapper.findAll('.el-dialog button.el-button');
    const confirmBtn = dialogButtons.find((b) => b.text().includes('确认'));
    await confirmBtn!.trigger('click');
    await flushPromises();

    // First standard reason value is the主料不同 option (dropdown, not free text)
    expect(mockRejectQueue).toHaveBeenCalledWith(201, '主料不同，非同一道菜');
    expect(elMessageSuccess).toHaveBeenCalled();
  });

  // ── Test 10 (P4a): dish empty state gives next-action ───────────────────

  it('Test 10: dish empty state shows the canonicalize re-run guidance', async () => {
    mockListQueue.mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 50 });
    mockGetAdminCount.mockResolvedValue(2);

    const wrapper = mount(DataQualityQueue, {
      global: {
        stubs: globalStubs,
        directives: { loading: vLoadingStub },
      },
    });
    await flushPromises();

    const vm = wrapper.vm as unknown as { onTabChange: (n: string) => void };
    vm.onTabChange('dish');
    await flushPromises();

    const html = wrapper.html();
    expect(html).toContain('暂无待归一菜品');
    expect(html).toContain('generate_dish_canonical_candidates');
  });
});

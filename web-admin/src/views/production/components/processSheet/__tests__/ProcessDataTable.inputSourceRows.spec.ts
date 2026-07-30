import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { WorkflowPortDescriptor, WorkflowProcessDescriptor } from '@/api/processSheet';

const submitRow = vi.fn();
const getSemiFinishedInventory = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: vi.fn().mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT' } }),
    submitRow: (...args: unknown[]) => submitRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getSemiFinishedInventory: (...args: unknown[]) => getSemiFinishedInventory(...args),
    getFinishedGoodsInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});

vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));

import ProcessDataTable from '../ProcessDataTable.vue';

/**
 * 客户 2026-07-30 (刘山门 / 六膳门 F006 报工):
 *  - 半成品/成品投入那一块「视觉上很散」—— 每条来源是一条没有表头的 flex 行, 品名/下拉/数量/
 *    单位/余量/两个按钮横着排, 看不出哪一列是什么。
 *  - 「只有单个批次时自动选中, 不要让用户多点一次」。
 *  - 数量仍然由人填 —— 投入不一定等于上一道产出 (损耗 / 只用一部分 / 补料)。
 *
 * 这些是行为测试: 真挂载组件, 断言操作员看到什么、提交出去的是什么。源码断言只能证明
 * 「代码长这样」, 证明不了「这块 UI 真的在工作」。
 */

const OUTPUT_PORT: WorkflowProcessDescriptor['outputs'][number] = {
  workflowPortId: 'OUT-1', materialNodeId: 'OUT-NODE-1', materialKind: 'SEMI_FINISHED',
  skuId: 'OUT-1', materialName: '成品半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
};

function context(inputs: WorkflowPortDescriptor[], allowMulti = false): WorkflowProcessDescriptor {
  return {
    workflowNodeId: 'PROC-1', workProcessId: 'WP-1', processName: '合流工序', processCategory: 'MERGE',
    defaultCostCategory: null, processOrder: 2, plannedUnit: 'kg',
    allowMultipleUpstreamSources: allowMulti, allowFinishedGoodsSource: false, customFieldSchema: null,
    inputs, output: OUTPUT_PORT, outputs: [OUTPUT_PORT],
  };
}

function port(over: Partial<WorkflowPortDescriptor> = {}): WorkflowPortDescriptor {
  return {
    workflowPortId: 'IN-1', materialNodeId: 'NODE-1', materialKind: 'SEMI_FINISHED',
    skuId: 'SEMI-1', materialName: '上游半成品', unit: 'kg', required: true,
    skuResolved: true, finished: false, ...over,
  };
}

function wip(batchNumber: string, productTypeId = 'SEMI-1', remaining = 10) {
  return {
    batchNumber, productTypeId, productTypeName: '上游半成品',
    produced: remaining, used: 0, remaining, status: 'ACTIVE' as const, unit: 'kg',
  };
}

function mountTable(options: {
  inputs?: WorkflowPortDescriptor[];
  allowMulti?: boolean;
  upstreamItems?: ReturnType<typeof wip>[];
  viewMode?: 'card' | 'grid';
  initialRows?: unknown[];
} = {}) {
  const inputs = options.inputs ?? [port()];
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'custom_merge', processOrder: 2,
      processLabel: '合流工序', productTypeId: 'OUT-1', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: false, initialRows: options.initialRows ?? [], ownInventoryItems: [],
      upstreamItems: options.upstreamItems ?? [wip('UP-ONLY')],
      viewMode: options.viewMode ?? 'card',
      workflowContext: context(inputs, options.allowMulti ?? false),
      allowMultipleUpstreamSources: options.allowMulti ?? false,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  await wrapper.findAll('button').find((item) => item.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

/**
 * 多来源: 保证混批区是展开的。
 *
 * ⚠️ 不能无脑点一下 —— blankRow() 里 `mixExpanded = workflowUpstreamInputs.length > 1`,
 * 端口多于一个时本来就是展开的, 再点一下反而收起来 (写这组用例时就先踩了这一脚)。
 */
async function expandSources(wrapper: ReturnType<typeof mountTable>) {
  if (wrapper.find('[data-testid="upstream-source-line"]').exists()) return;
  const cardToggle = wrapper.find('[data-testid="upstream-sources-toggle"] button');
  const toggle = cardToggle.exists()
    // 表格模式的展开按钮在来源列里, 没有单独的 testid
    ? cardToggle
    : wrapper.findAll('td button').find((b) => b.text().includes('批'))!;
  await toggle.trigger('click');
  await flushPromises();
}

describe('半成品/成品投入的行式布局与唯一候选自动选中 (客户 2026-07-30)', () => {
  beforeEach(() => {
    submitRow.mockReset();
    getSemiFinishedInventory.mockReset();
    getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
    submitRow.mockResolvedValue({
      success: true,
      data: { submissionStatus: 'SUBMITTED', materialized: true, batchNumber: 'WIP-OUT-1' },
    });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  // ---------------------------------------------------------------------
  // 唯一候选批次自动选中
  // ---------------------------------------------------------------------

  it('单上游只有一个批次时直接显示批次, 不给下拉 (客户: 不要让用户多点一次)', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    const fixed = wrapper.find('[data-testid="upstream-batch-fixed"]');
    expect(fixed.exists()).toBe(true);
    expect(fixed.text()).toContain('UP-ONLY');
    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(false);
  });

  it('自动选中的批次真的落进提交的 payload, 不只是显示出来', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    // 单上游的通用工序仍要填「投入(kg)」—— 自动选中只免掉「挑哪一批」那一下, 数量照旧实填。
    wrapper.findAll('.sp-card-field').find((f) => f.text().includes('投入(kg)'))!
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 7);
    await flushPromises();
    await wrapper.findAll('button').find((b) => b.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(submitRow).toHaveBeenCalledTimes(1);
    const request = submitRow.mock.calls[0][2] as Record<string, unknown>;
    expect(request.upstreamSources).toEqual([
      expect.objectContaining({ sourceBatchNumber: 'UP-ONLY', semiFinished: false, finishedGoods: false }),
    ]);
  });

  it('有第二个批次时照常给下拉, 绝不替操作员挑一个', async () => {
    const wrapper = mountTable({ upstreamItems: [wip('UP-A'), wip('UP-B')] });
    await flushPromises();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="upstream-batch-fixed"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(true);
  });

  it('余量为 0 的批次不算候选 —— 剩下唯一一个能用的仍然自动选中', async () => {
    const wrapper = mountTable({ upstreamItems: [wip('UP-EMPTY', 'SEMI-1', 0), wip('UP-USABLE')] });
    await flushPromises();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="upstream-batch-fixed"]').text()).toContain('UP-USABLE');
  });

  it('在下拉里打字把候选筛成一条, 不会因此被自动选中 (判据必须用未过滤的全量候选)', async () => {
    const wrapper = mountTable({ upstreamItems: [wip('UP-A'), wip('UP-B')] });
    await flushPromises();
    await addRow(wrapper);

    // 下拉的搜索词只影响「列出哪些选项」, 不能影响「是不是只有一条候选」——
    // 否则操作员打个字就会被自动选中一个他并没有挑的批次。
    const select = wrapper.findComponent({ name: 'ElSelect' });
    select.vm.$emit('visible-change', true);
    (select.props('filterMethod') as (query: string) => void)('UP-A');
    await flushPromises();

    expect(wrapper.find('[data-testid="upstream-batch-fixed"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(true);
  });

  it('半成品库存还在拉取时不抢着自动选中 —— 加载完才发现其实有好几条', async () => {
    let releaseSfi: ((value: unknown) => void) | undefined;
    getSemiFinishedInventory.mockReturnValue(new Promise((res) => { releaseSfi = res; }));

    // 已存在的草稿行在库存还没拉回来时就已经渲染出来了 —— 那一刻只看得到 1 条 in-plan WIP,
    // 抢着自动选中就等于替操作员选了个他没得比较的批次。
    const wrapper = mountTable({
      upstreamItems: [wip('UP-ONLY')],
      initialRows: [{
        clientRowId: 'row-draft-1', batchNumber: null, rowStatus: 'DRAFT',
        submissionStatus: 'DRAFT', materialized: false, interimSettledAt: null,
        payload: { clientRowId: 'row-draft-1', processCode: 'custom_merge', processOrder: 2, productTypeId: 'OUT-1' },
      }],
    });
    await flushPromises();
    expect(wrapper.findAll('[data-testid="upstream-source-line"], .sp-card').length).toBeGreaterThan(0);
    expect(wrapper.find('[data-testid="upstream-batch-fixed"]').exists()).toBe(false);

    releaseSfi!({
      success: true,
      data: [
        { intermediateBatchNo: 'SFI-1', productTypeId: 'SEMI-1', productTypeName: '公共半成品', availableQuantity: 4, remainingQuantity: 4, unit: 'kg' },
        { intermediateBatchNo: 'SFI-2', productTypeId: 'SEMI-1', productTypeName: '公共半成品', availableQuantity: 5, remainingQuantity: 5, unit: 'kg' },
      ],
    });
    await flushPromises();

    // 真实候选是 3 条 → 照常给下拉, 什么都没被替他选掉
    expect(wrapper.find('[data-testid="upstream-batch-fixed"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(true);
  });

  it('端口还在替代关系里、操作员没勾选用时不自动选批次 (那是「选哪个端口」的决定)', async () => {
    const group = {
      selectionGroupId: 'g1', selectionGroupLabel: '上游替代料',
      selectionGroupMode: 'EXACTLY_ONE' as const,
      selectionGroupMinSelections: 1, selectionGroupMaxSelections: 1,
    };
    const wrapper = mountTable({
      allowMulti: true,
      inputs: [
        port({ workflowPortId: 'IN-1', skuId: 'SEMI-1', materialName: '主料', ...group }),
        port({ workflowPortId: 'IN-2', materialNodeId: 'NODE-2', skuId: 'SEMI-2', materialName: '替代料', ...group }),
      ],
      upstreamItems: [wip('UP-A', 'SEMI-1'), wip('UP-B', 'SEMI-2')],
    });
    await flushPromises();
    await addRow(wrapper);
    await expandSources(wrapper);

    const lines = wrapper.findAll('[data-testid="upstream-source-line"]');
    expect(lines).toHaveLength(2);
    // 还没勾「选用」→ 两行都仍然是可选下拉, 没有被替他定下批次
    expect(wrapper.findAll('[data-testid="upstream-batch-fixed"]')).toHaveLength(0);

    // 勾上第二条之后, 这一行就只剩「哪一批」这一个问题 → 唯一候选直接落下去
    lines[1].findComponent({ name: 'ElCheckbox' }).vm.$emit('change', true);
    await flushPromises();
    expect(wrapper.findAll('[data-testid="upstream-source-line"]')[1]
      .find('[data-testid="upstream-batch-fixed"]').text()).toContain('UP-B');
  });

  // ---------------------------------------------------------------------
  // 行式布局 (选用 + 名称 + 数量 + 来源批次)
  // ---------------------------------------------------------------------

  it('多来源投入排成带表头的行, 每条一行 (原来是没有表头的 flex 行)', async () => {
    const wrapper = mountTable({
      allowMulti: true,
      inputs: [
        port({ workflowPortId: 'IN-1', skuId: 'SEMI-1', materialName: '主料' }),
        port({ workflowPortId: 'IN-2', materialNodeId: 'NODE-2', skuId: 'SEMI-2', materialName: '辅料' }),
      ],
      upstreamItems: [wip('UP-A', 'SEMI-1'), wip('UP-B', 'SEMI-2')],
    });
    await flushPromises();
    await addRow(wrapper);
    await expandSources(wrapper);

    const head = wrapper.find('.sp-src-head');
    expect(head.exists()).toBe(true);
    expect(head.text()).toContain('投入物料');
    expect(head.text()).toContain('投入数量');
    expect(head.text()).toContain('来源批次');
    expect(head.attributes('aria-hidden')).toBe('true');

    const lines = wrapper.findAll('[data-testid="upstream-source-line"]');
    expect(lines.map((line) => line.find('[data-testid="input-port-name"]').text())).toEqual(['主料', '辅料']);
  });

  it('每个投入控件都带得起身份 (防呆 Rule 2: aria-label 含品名)', async () => {
    const wrapper = mountTable({
      allowMulti: true,
      inputs: [port({ materialName: '主料' })],
      upstreamItems: [wip('UP-A'), wip('UP-B')],
    });
    await flushPromises();
    await addRow(wrapper);
    await expandSources(wrapper);

    const line = wrapper.find('[data-testid="upstream-source-line"]');
    const labels = line.findAll('[aria-label]').map((node) => node.attributes('aria-label'));
    expect(labels).toContain('主料 投入数量');
    expect(labels).toContain('主料 来源批次');
    expect(labels).toContain('为 主料 再加一个来源批次');
  });

  it('两套模板都渲染同一个行式投入表 —— 表格模式(默认视图)不能漏', async () => {
    for (const viewMode of ['card', 'grid'] as const) {
      const wrapper = mountTable({
        viewMode,
        allowMulti: true,
        inputs: [port({ materialName: '主料' })],
        upstreamItems: [wip('UP-A'), wip('UP-B')],
      });
      await flushPromises();
      await addRow(wrapper);
      await expandSources(wrapper);

      expect(wrapper.find('.sp-src-head').exists()).toBe(true);
      expect(wrapper.findAll('[data-testid="upstream-source-line"]')).toHaveLength(1);
      expect(wrapper.find('[aria-label="主料 投入数量"]').exists()).toBe(true);
      wrapper.unmount();
    }
  });

  // ---------------------------------------------------------------------
  // 数量仍然由人填
  // ---------------------------------------------------------------------

  it('自动选中批次不代表替操作员把数量填上 (投入 ≠ 上一道产出)', async () => {
    const wrapper = mountTable({
      allowMulti: true,
      inputs: [port({ materialName: '主料' })],
      upstreamItems: [wip('UP-ONLY', 'SEMI-1', 9)],
    });
    await flushPromises();
    await addRow(wrapper);
    await expandSources(wrapper);

    const line = wrapper.find('[data-testid="upstream-source-line"]');
    expect(line.find('[data-testid="upstream-batch-fixed"]').text()).toContain('UP-ONLY');
    // 批次的可用量是 9kg, 但数量框不会被自动填成 9 —— 非成品道一律实填
    expect(line.findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(0);

    line.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 6);
    wrapper.find('[data-testid="workflow-output-line"] [data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 5);
    await flushPromises();
    await wrapper.findAll('button').find((b) => b.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    const request = submitRow.mock.calls[0][2] as Record<string, unknown>;
    expect(request.upstreamSources).toEqual([
      expect.objectContaining({ sourceBatchNumber: 'UP-ONLY', feedQuantityKg: 6 }),
    ]);
  });
});

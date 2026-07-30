import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';

const saveDraftRow = vi.fn();
const submitRow = vi.fn();
const getSemiFinishedInventory = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: (...args: unknown[]) => saveDraftRow(...args),
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

const workflowContext = {
  workflowNodeId: 'PROC-FINAL',
  workProcessId: 'WP-FINAL',
  processName: '冷冻',
  processCategory: 'PACKAGING',
  defaultCostCategory: null,
  processOrder: 2,
  plannedUnit: '盒',
  allowMultipleUpstreamSources: false,
  allowFinishedGoodsSource: false,
  customFieldSchema: null,
  inputs: [{
    workflowPortId: 'IN-1', materialNodeId: 'SEMI-NODE-1', materialKind: 'SEMI_FINISHED',
    skuId: 'SEMI-1', materialName: '羊排熟制半成品', unit: 'kg', required: true,
    skuResolved: true, finished: false,
  }],
  output: {
    workflowPortId: 'OUT-1', materialNodeId: 'SKU-NODE-1', materialKind: 'FINISHED_GOOD',
    skuId: 'SKU-1', materialName: '香辣孜然羊排', unit: '盒', gramsPerUnit: 200,
    required: true, skuResolved: true, finished: true,
  },
  outputs: [{
    workflowPortId: 'OUT-1', materialNodeId: 'SKU-NODE-1', materialKind: 'FINISHED_GOOD',
    skuId: 'SKU-1', materialName: '香辣孜然羊排', unit: '盒', gramsPerUnit: 200,
    required: true, skuResolved: true, finished: true,
  }],
};

const availableUpstream = [{
  batchNumber: 'WIP-AVAILABLE-1', produced: 12, used: 0, remaining: 12,
  status: 'ACTIVE', unit: 'kg', productTypeName: '羊排熟制半成品',
}];

function mountTable(
  context: unknown = workflowContext,
  upstreamItems: unknown[] = availableUpstream,
  initialRows: unknown[] = [],
) {
  const typedContext = context as { allowMultipleUpstreamSources?: boolean } | null;
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'qidiao', processOrder: 2,
      processLabel: '冷冻', productTypeId: 'SKU-1', inputUnit: 'kg', outputUnit: '盒',
      isFirstProcess: false, upstreamItems, ownInventoryItems: [], initialRows,
      viewMode: 'card', workflowContext: context,
      allowMultipleUpstreamSources: typedContext?.allowMultipleUpstreamSources ?? false,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  const button = wrapper.findAll('button').find((item) => item.text().includes('新增行'));
  if (!button) throw new Error('找不到新增行按钮');
  await button.trigger('click');
  await flushPromises();
}

function setPrimaryOutput(wrapper: ReturnType<typeof mountTable>, quantity: number) {
  wrapper.find('[data-testid="workflow-output-line"] [data-testid="output-quantity"]')
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', quantity);
}

/**
 * 定下这一行的来源批次。
 *
 * 客户 2026-07-30 之后, 可选批次只有一条时界面不再渲染下拉, 父组件已经替操作员按同一条路径
 * (onUpstreamSelect / onSingleUpstreamSelect) 选好了 ——「只有一个批次时自动选中, 不要让用户
 * 多点一次」。断言因此改成「要么点得到下拉, 要么已经自动选中并把批次显示出来」, 用例原本要证明
 * 的事 (这一行最终用的是哪一批、投入量怎么算) 一个没少。
 */
async function pickUpstreamBatch(
  scope: ReturnType<typeof mountTable> | ReturnType<ReturnType<typeof mountTable>['find']>,
  compositeKey: string,
) {
  const select = scope.findComponent({ name: 'ElSelect' });
  if (select.exists()) {
    select.vm.$emit('change', compositeKey);
    await flushPromises();
    return;
  }
  const fixed = scope.find('[data-testid="upstream-batch-fixed"]');
  if (!fixed.exists()) throw new Error('既没有来源批次下拉, 也没有自动选中的批次');
  expect(fixed.text()).toContain(compositeKey.slice(compositeKey.indexOf('::') + 2));
  await flushPromises();
}

describe('ProcessDataTable finished-goods reporting contract', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    saveDraftRow.mockReset();
    submitRow.mockReset();
    getSemiFinishedInventory.mockReset();
    saveDraftRow.mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT', materialized: false } });
    submitRow.mockResolvedValue({ success: true, data: { batchNumber: 'FG-1', submissionStatus: 'SUBMITTED', materialized: true } });
    getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('uses the Workflow-fixed finished output and derives weight from SKU net weight', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    expect(wrapper.text()).toContain('产出数量');
    expect(wrapper.text()).toContain('香辣孜然羊排');
    expect(wrapper.text()).toContain('盒');
    expect(wrapper.text()).not.toContain('领用');
    expect(wrapper.text()).not.toContain('使用重量');
    expect(wrapper.text()).not.toContain('手工成品重');
    expect(wrapper.text()).not.toContain('单盒克重');
    expect(wrapper.text()).not.toContain('料头');

    setPrimaryOutput(wrapper, 50);
    await flushPromises();

    expect(wrapper.text()).toContain('10 kg');
  });

  it('renders canonical box as 盒 in a persisted finished row without changing its payload unit', async () => {
    const context = JSON.parse(JSON.stringify(workflowContext));
    context.output.unit = 'box';
    context.outputs[0].unit = 'box';
    const persistedRow = {
      clientRowId: 'qidiao-mrs9qy4k-34301c',
      batchNumber: 'PB-PLAN-TEST-64467',
      batchId: 10588,
      rowStatus: 'SAVED',
      submissionStatus: 'SUBMITTED',
      materialized: true,
      interimSettledAt: null,
      payload: {
        clientRowId: 'qidiao-mrs9qy4k-34301c', processCode: 'qidiao', processOrder: 2,
        productTypeId: 'SKU-1', finished: true, inputQuantity: 4.5, outputQuantity: 5,
        inputUnit: 'kg', outputUnit: 'box', unit: 'box', productWeight: 4,
        seasoningStep: false,
      },
    };

    const wrapper = mountTable(context, [], [persistedRow]);
    await flushPromises();

    expect(wrapper.text()).toContain('实产 5 盒');
    expect(wrapper.text()).not.toContain('实产 5 box');
    expect(persistedRow.payload.unit).toBe('box');
  });

  it('shows an explicit missing-net-weight message instead of inventing a conversion', async () => {
    const context = JSON.parse(JSON.stringify(workflowContext));
    delete context.output.gramsPerUnit;
    delete context.outputs[0].gramsPerUnit;
    const wrapper = mountTable(context);
    await flushPromises();
    await addRow(wrapper);
    expect(wrapper.text()).toContain('未配置单位净重，无法计算成品重量');
  });

  it('automatically uses the selected upstream WIP remaining weight as the finished-step formal input', async () => {
    const wrapper = mountTable(workflowContext, [{
      batchNumber: 'WIP-UPSTREAM-1', produced: 12, used: 0, remaining: 12,
      status: 'ACTIVE', unit: 'kg', productTypeName: '羊排熟制半成品',
    }]);
    await flushPromises();
    await addRow(wrapper);

    await pickUpstreamBatch(wrapper, 'wip::WIP-UPSTREAM-1');
    setPrimaryOutput(wrapper, 50);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(submitRow).toHaveBeenCalledTimes(1);
    const request = submitRow.mock.calls[0][2] as Record<string, unknown>;
    expect(request.inputQuantity).toBe(12);
    expect(request.upstreamSources).toEqual([expect.objectContaining({
      sourceBatchNumber: 'WIP-UPSTREAM-1', feedQuantityKg: 12,
    })]);
  });

  it('auto-fills a multi-source finished row from the selected in-plan WIP and keeps preview/payload identical after remount', async () => {
    const context = JSON.parse(JSON.stringify(workflowContext));
    context.allowMultipleUpstreamSources = true;
    const upstream = [{
      batchNumber: 'CLK-W-20260720-9232', produced: 4.5, used: 0, remaining: 4.5,
      status: 'ACTIVE', unit: 'kg', productTypeId: 'SEMI-1', productTypeName: '处理后半成品',
    }];

    for (let attempt = 0; attempt < 2; attempt += 1) {
      const wrapper = mountTable(context, upstream);
      await flushPromises();
      await addRow(wrapper);
      await wrapper.find('[data-testid="upstream-sources-toggle"] button').trigger('click');
      const sourceLine = wrapper.find('[data-testid="upstream-source-line"]');
      await pickUpstreamBatch(sourceLine, 'wip::CLK-W-20260720-9232');
      setPrimaryOutput(wrapper, 5);
      await flushPromises();

      expect(sourceLine.findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(4.5);
      await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
      await flushPromises();

      const preview = vi.mocked(ElMessageBox.confirm).mock.calls.at(-1)?.[0];
      expect(String(preview)).toContain('CLK-W-20260720-9232 4.5kg');
      const request = submitRow.mock.calls.at(-1)?.[2] as Record<string, unknown>;
      expect(request.inputQuantity).toBe(4.5);
      expect(request.outputQuantity).toBe(5);
      expect(request.upstreamSources).toEqual([expect.objectContaining({
        workflowPortId: 'IN-1', skuId: 'SEMI-1', sourceBatchNumber: 'CLK-W-20260720-9232',
        feedQuantityKg: 4.5, semiFinished: false, finishedGoods: false,
      })]);
      wrapper.unmount();
    }
    expect(submitRow).toHaveBeenCalledTimes(2);
  });

  it('auto-fills a public semi-finished batch in kg and preserves its source identity in preview and payload', async () => {
    const context = JSON.parse(JSON.stringify(workflowContext));
    context.allowMultipleUpstreamSources = true;
    getSemiFinishedInventory.mockResolvedValue({
      success: true,
      data: [{
        intermediateBatchNo: 'SFI-PUBLIC-1', productTypeId: 'SEMI-1', productTypeName: '公共半成品',
        availableQuantity: 3.25, remainingQuantity: 3.25, unit: 'kg', productionDate: '2026-07-20',
      }],
    });
    const wrapper = mountTable(context, []);
    await flushPromises();
    await addRow(wrapper);
    await wrapper.find('[data-testid="upstream-sources-toggle"] button').trigger('click');
    const sourceLine = wrapper.find('[data-testid="upstream-source-line"]');
    await pickUpstreamBatch(sourceLine, 'sfi::SFI-PUBLIC-1');
    setPrimaryOutput(wrapper, 5);
    await flushPromises();

    expect(sourceLine.findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(3.25);
    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(String(vi.mocked(ElMessageBox.confirm).mock.calls.at(-1)?.[0])).toContain('SFI-PUBLIC-1 3.25kg');
    const request = submitRow.mock.calls.at(-1)?.[2] as Record<string, unknown>;
    expect(request.inputQuantity).toBe(3.25);
    expect(request.upstreamSources).toEqual([expect.objectContaining({
      sourceBatchNumber: 'SFI-PUBLIC-1', feedQuantityKg: 3.25, semiFinished: true,
    })]);
  });

  it('fails closed instead of submitting zero when the selected upstream stock cannot be resolved', async () => {
    // 两个可选批次 → 界面照常给下拉 (唯一候选才自动选中), 才能模拟「选了一个已经不在库里的批次」。
    const wrapper = mountTable(workflowContext, [
      ...availableUpstream,
      { batchNumber: 'WIP-AVAILABLE-2', produced: 8, used: 0, remaining: 8, status: 'ACTIVE', unit: 'kg', productTypeName: '羊排熟制半成品' },
    ]);
    await flushPromises();
    await addRow(wrapper);

    const picker = wrapper.findComponent({ name: 'ElSelect' });
    expect(picker.exists()).toBe(true);
    picker.vm.$emit('change', 'wip::STALE-BATCH');
    setPrimaryOutput(wrapper, 50);
    await flushPromises();

    const submitButton = wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!;
    expect(submitButton.attributes('disabled')).toBeDefined();
    await submitButton.trigger('click');
    await flushPromises();

    expect(submitRow).not.toHaveBeenCalled();
  });

  it('disables add-row when a downstream workflow step has no available source inventory', async () => {
    const wrapper = mountTable(workflowContext, []);
    await flushPromises();

    const addButton = wrapper.find('[data-testid="add-process-row"]');
    expect(addButton.attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('暂无可用上游库存');
    expect(wrapper.text()).toContain('请先完成上游报工或联系仓管补料');
  });
});

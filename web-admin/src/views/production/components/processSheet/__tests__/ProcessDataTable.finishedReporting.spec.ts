import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const saveDraftRow = vi.fn();
const submitRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: (...args: unknown[]) => saveDraftRow(...args),
    submitRow: (...args: unknown[]) => submitRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getSemiFinishedInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
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

function mountTable(context: unknown = workflowContext, upstreamItems: unknown[] = []) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'qidiao', processOrder: 2,
      processLabel: '冷冻', productTypeId: 'SKU-1', inputUnit: 'kg', outputUnit: '盒',
      isFirstProcess: true, upstreamItems, ownInventoryItems: [], initialRows: [],
      viewMode: 'card', workflowContext: context,
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

describe('ProcessDataTable finished-goods reporting contract', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    saveDraftRow.mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT', materialized: false } });
    submitRow.mockResolvedValue({ success: true, data: { batchNumber: 'FG-1', submissionStatus: 'SUBMITTED', materialized: true } });
  });

  it('only asks for actual production and sample, then derives inbound, remaining and weight from SKU net weight', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    expect(wrapper.text()).toContain('实际生产(盒)');
    expect(wrapper.text()).toContain('留样(盒)');
    expect(wrapper.text()).toContain('入库(盒)');
    expect(wrapper.text()).toContain('剩余(盒)');
    expect(wrapper.text()).toContain('单位净重');
    expect(wrapper.text()).not.toContain('领用');
    expect(wrapper.text()).not.toContain('使用重量');
    expect(wrapper.text()).not.toContain('手工成品重');
    expect(wrapper.text()).not.toContain('单盒克重');
    expect(wrapper.text()).not.toContain('料头');

    const actualField = wrapper.findAll('.sp-card-field').find((item) => item.text().includes('实际生产'))!;
    const sampleField = wrapper.findAll('.sp-card-field').find((item) => item.text().includes('留样'))!;
    actualField.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 50);
    sampleField.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 2);
    await flushPromises();

    expect(wrapper.text()).toContain('48');
    expect(wrapper.text()).toContain('10 kg');
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

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'wip::WIP-UPSTREAM-1');
    const actualField = wrapper.findAll('.sp-card-field').find((item) => item.text().includes('实际生产'))!;
    actualField.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 50);
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

  it('fails closed instead of submitting zero when the selected upstream stock cannot be resolved', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'wip::STALE-BATCH');
    const actualField = wrapper.findAll('.sp-card-field').find((item) => item.text().includes('实际生产'))!;
    actualField.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 50);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(submitRow).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('无法从所选上游库存确定实际投入量');
  });
});

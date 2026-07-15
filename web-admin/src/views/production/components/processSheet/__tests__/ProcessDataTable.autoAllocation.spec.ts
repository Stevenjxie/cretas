import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { ApiError } from '@/types/api';

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
  workflowNodeId: 'P-RAW', workProcessId: 'WP-RAW', processName: '修切', processCategory: 'PREP',
  defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg', allowMultipleUpstreamSources: false,
  allowFinishedGoodsSource: false, customFieldSchema: null,
  inputs: [{
    workflowPortId: 'IN-BEEF', materialNodeId: 'RAW-BEEF-NODE', materialKind: 'RAW_MATERIAL',
    skuId: 'RAW-BEEF', materialName: '牛肉', unit: 'kg', required: true, skuResolved: true, finished: false,
  }],
  output: {
    workflowPortId: 'OUT-SEMI', materialNodeId: 'SEMI-NODE', materialKind: 'SEMI_FINISHED',
    skuId: 'SEMI-BEEF', materialName: '修切后牛肉', unit: 'kg', required: true, skuResolved: true, finished: false,
  },
  outputs: [{
    workflowPortId: 'OUT-SEMI', materialNodeId: 'SEMI-NODE', materialKind: 'SEMI_FINISHED',
    skuId: 'SEMI-BEEF', materialName: '修切后牛肉', unit: 'kg', required: true, skuResolved: true, finished: false,
  }],
};

function mountTable() {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'xiuyou', processOrder: 1,
      processLabel: '修切', productTypeId: 'SEMI-BEEF', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: true, initialRows: [], upstreamItems: [], ownInventoryItems: [],
      viewMode: 'card', workflowContext,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  const button = wrapper.findAll('button').find((item) => item.text().includes('新增行'))!;
  await button.trigger('click');
  await flushPromises();
}

describe('ProcessDataTable production-store automatic allocation', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    saveDraftRow.mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT', materialized: false } });
    submitRow.mockResolvedValue({ success: true, data: { submissionStatus: 'SUBMITTED', materialized: true, batchNumber: 'WIP-1' } });
  });

  it('asks for material totals without a source batch and exposes separate draft and submit actions', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="material-input-total"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('牛肉');
    expect(wrapper.text()).toContain('投料总量(kg)');
    expect(wrapper.find('[data-testid="legacy-raw-batch-picker"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('保存草稿');
    expect(wrapper.text()).toContain('正式报工');
  });

  it('sends materialInputTotals to draft and submit endpoints without rawMaterialInputs', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
    const outputField = wrapper.findAll('.sp-card-field').find((item) => item.text().includes('产出'))!;
    outputField.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('保存草稿'))!.trigger('click');
    await flushPromises();
    const draftRequest = saveDraftRow.mock.calls[0][2] as Record<string, unknown>;
    expect(draftRequest.materialInputTotals).toEqual([{
      materialTypeId: 'RAW-BEEF', quantity: 10, unit: 'kg', workflowPortId: 'IN-BEEF', materialNodeId: 'RAW-BEEF-NODE',
    }]);
    expect(draftRequest.rawMaterialInputs).toBeUndefined();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();
    expect(submitRow).toHaveBeenCalledTimes(1);
  });

  it('keeps the row editable and shows the backend shortage message after submit is rejected', async () => {
    submitRow.mockRejectedValue(new ApiError(
      '当前只能保存草稿，生产库中投料量不足，请联系仓管补料',
      'PRODUCTION_STOCK_SHORTAGE',
      409,
    ));
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);
    wrapper.find('[data-testid="material-input-total"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
    const outputField = wrapper.findAll('.sp-card-field').find((item) => item.text().includes('产出'))!;
    outputField.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('当前只能保存草稿，生产库中投料量不足，请联系仓管补料');
    expect(wrapper.text()).toContain('保存草稿');
  });
});

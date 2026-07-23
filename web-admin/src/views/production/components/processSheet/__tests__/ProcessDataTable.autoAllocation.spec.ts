import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { ElMessageBox } from 'element-plus';
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

function mountTable(initialRows: Record<string, unknown>[] = []) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'xiuyou', processOrder: 1,
      processLabel: '修切', productTypeId: 'SEMI-BEEF', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: true, initialRows, upstreamItems: [], ownInventoryItems: [],
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
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('asks for material totals without a source batch and exposes separate draft and submit actions', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="material-input-total"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('牛肉');
    expect(wrapper.text()).toContain('投料总量');
    expect(wrapper.find('[data-testid="legacy-raw-batch-picker"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('保存草稿');
    expect(wrapper.text()).toContain('正式报工');
  });

  it('sends materialInputTotals to draft and submit endpoints without rawMaterialInputs', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:model-value', 8);
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
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:model-value', 8);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('当前只能保存草稿，生产库中投料量不足，请联系仓管补料');
    expect(wrapper.text()).toContain('保存草稿');
  });

  it('shows the actual packaging batch, native quantity, unit price and cost after formal reporting', async () => {
    submitRow.mockResolvedValue({
      success: true,
      data: {
        submissionStatus: 'SUBMITTED',
        materialized: true,
        batchNumber: 'WIP-1',
        inputAllocations: [{
          materialTypeId: 'PACK-BOX',
          materialName: '800g 包装盒',
          materialBatchId: 'PACK-BATCH-ID-1',
          batchNumber: 'PACK-20260724-01',
          quantity: 10,
          unit: 'box',
          sourceType: 'PACKAGING',
          unitPrice: 0.65,
          totalCost: 6.5,
          automatic: true,
          allocationOrder: 1,
        }],
      },
    });
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);
    wrapper.find('[data-testid="material-input-total"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' })
      .vm.$emit('update:model-value', 8);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    const result = wrapper.get('[data-testid="automatic-input-allocations"]');
    expect(result.text()).toContain('包材');
    expect(result.text()).toContain('800g 包装盒');
    expect(result.text()).toContain('PACK-20260724-01');
    expect(result.text()).toContain('10 盒');
    expect(result.text()).toContain('¥0.6500/盒');
    expect(result.text()).toContain('成本 ¥6.50');
  });

  it('keeps materialized LEGACY rows read-only while every new row uses workflow material totals', async () => {
    const wrapper = mountTable([{
      clientRowId: 'legacy-row-1', batchNumber: 'WIP-LEGACY-1', batchId: 1,
      rowStatus: 'SAVED', submissionStatus: 'LEGACY', materialized: true,
      payload: {
        clientRowId: 'legacy-row-1', processCode: 'xiuyou', processOrder: 1,
        rawMaterialInputs: [{ materialBatchId: 'RAW-BATCH-1', quantity: 12 }],
        outputQuantity: 100_000,
        outputUnit: 'g',
        unit: 'g',
      },
    }]);
    await flushPromises();

    expect(wrapper.text()).toContain('历史数据（只读）');
    expect(wrapper.find('[data-testid="legacy-raw-batch-picker"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="legacy-readonly-row"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('产出 100.00 kg');
    expect(wrapper.text()).not.toContain('产出 100000.00 kg');
    expect(wrapper.text()).not.toContain('保存草稿');

    await addRow(wrapper);
    expect(wrapper.findAll('[data-testid="material-input-total"]')).toHaveLength(1);
    expect(wrapper.find('[data-testid="legacy-raw-batch-picker"]').exists()).toBe(false);
  });
});

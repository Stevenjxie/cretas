import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';

const getAvailableRawBatches = vi.fn();
const listWarehouses = vi.fn();
const submitRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: vi.fn().mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT' } }),
    submitRow: (...args: unknown[]) => submitRow(...args),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getAvailableRawBatches: (...args: unknown[]) => getAvailableRawBatches(...args),
    getSemiFinishedInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
    getFinishedGoodsInventory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});

vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: (...args: unknown[]) => listWarehouses(...args),
}));

import ProcessDataTable from '../ProcessDataTable.vue';

/** 单一 kg 原料端口 (与 autoAllocation.spec 同形状)。 */
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

function batch(over: Record<string, unknown>) {
  return {
    id: 'B-x', batchNumber: 'MT-1', materialTypeId: 'RAW-BEEF', materialName: '牛肉',
    materialTypeName: '牛肉', warehouseId: 'WH-WKS-1', sourceDocType: 'MATERIAL_REQUISITION',
    currentQuantity: 0, quantity: 0, quantityUnit: 'kg', unit: 'kg', unitPrice: 10,
    ...over,
  };
}

function mountTable(context: unknown = workflowContext) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-1', processCode: 'xiuyou', processOrder: 1,
      processLabel: '修切', productTypeId: 'SEMI-BEEF', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: true, initialRows: [], upstreamItems: [], ownInventoryItems: [],
      viewMode: 'card', workflowContext: context,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  await wrapper.findAll('button').find((item) => item.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

/**
 * 投入行可用库存 —— 前端自算版**已于 2026-07-31 撤下**(客户实测与后端打架:
 * 行内显示「可用 10kg」而提交时后端说「可用 0kg」)。详见同目录
 * inputAvailableStock.source.spec.ts 头注释里的三条口径差异。
 *
 * 这组挂载测试现在守的是: **喂进真实批次, 界面也不许凭它自己算出一个数来**。
 * 后端只读接口接上之后, 这里再改写成断言"显示的是后端返回的那个数"。
 */
describe('投入行可用库存: 前端自算版已撤下', () => {
  beforeEach(() => {
    listWarehouses.mockReset();
    getAvailableRawBatches.mockReset();
    submitRow.mockReset();
    submitRow.mockResolvedValue({
      success: true,
      data: { submissionStatus: 'SUBMITTED', materialized: true, batchNumber: 'WIP-1' },
    });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    listWarehouses.mockResolvedValue({
      success: true,
      data: [{ id: 'WH-WKS-1', code: 'WH-WKS', name: '生产仓', type: 'WORKSHOP', isActive: true }],
    });
    getAvailableRawBatches.mockResolvedValue({ success: true, data: [] });
  });

  it('即使批次拉到了, 也不渲染任何可用量提示', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [
        batch({ id: 'B-1', batchNumber: 'MT-1', currentQuantity: 12, quantityUnit: 'kg', unit: 'kg' }),
        batch({ id: 'B-2', batchNumber: 'MT-2', currentQuantity: 500, quantityUnit: 'g', unit: 'g' }),
      ],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="input-available-stock"]').exists()).toBe(false);
    // 这些正是撤下前会渲染出来的数字 —— 12.5kg 恰好也是"看着很对"的那种错数字
    expect(wrapper.text()).not.toContain('可用 12.5');
    expect(wrapper.text()).not.toContain('单位不同');
  });

  it('填多少都不再被前端标红 (判据错了, 标红也是错的)', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [batch({ id: 'B-1', currentQuantity: 1, quantityUnit: 'kg', unit: 'kg' })],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 99);
    await flushPromises();

    expect(wrapper.find('.sp-in-stock-over').exists()).toBe(false);
  });

  it('提交路径没有被这次撤下影响 —— 仍由后端 fail-closed 兜底', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [batch({ id: 'B-1', currentQuantity: 1, quantityUnit: 'kg', unit: 'kg' })],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 99);
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
    await flushPromises();

    await wrapper.findAll('button').find((b) => b.text().includes('正式报工'))!.trigger('click');
    await flushPromises();
    expect(submitRow).toHaveBeenCalledTimes(1);
  });
});

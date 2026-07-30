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

/** 计数单位原料端口 —— 客户实际撞到的形态 (「需要 1只, 可用 0只」)。 */
const countingUnitContext = {
  ...workflowContext,
  inputs: [{
    workflowPortId: 'IN-CHICKEN', materialNodeId: 'RAW-CHICKEN-NODE', materialKind: 'RAW_MATERIAL',
    skuId: 'RAW-CHICKEN', materialName: '鸡', unit: '只', required: true, skuResolved: true, finished: false,
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
 * 客户 2026-07-30: 「生产仓有货, 报工却说可用 0只」—— 填完点「正式报工」才被后端告知
 * 「需要 1只, 可用 0只, 缺少 1只」。防呆 Rule 1: 把可用量摆到录入行。
 */
describe('原料投入行的可用库存 (行为)', () => {
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

  it('sums same-unit batches and converts g into the kg port unit', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [
        batch({ id: 'B-1', batchNumber: 'MT-1', currentQuantity: 12, quantityUnit: 'kg', unit: 'kg' }),
        batch({ id: 'B-2', batchNumber: 'MT-2', currentQuantity: 500, quantityUnit: 'g', unit: 'g' }),
      ],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    // 12kg + 500g = 12.5kg, 且不显示 12.500000
    expect(wrapper.find('[data-testid="input-available-stock"]').text()).toBe('可用 12.5kg');
  });

  it('discloses batches whose unit cannot be converted instead of silently adding or dropping them', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [
        batch({ id: 'B-1', batchNumber: 'MT-1', currentQuantity: 12, quantityUnit: 'kg', unit: 'kg' }),
        batch({ id: 'B-3', batchNumber: 'MT-3', currentQuantity: 2, quantityUnit: '箱', unit: '箱' }),
      ],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    // 12 + 2 = 14 是错的 (箱 ≠ kg, 而「每单位重量」桥已拍板暂不做); 也不能默默丢掉那 2 箱
    const text = wrapper.find('[data-testid="input-available-stock"]').text();
    expect(text).toBe('可用 12kg · 另有 2箱 单位不同, 未计入');
    expect(text).not.toContain('14');
  });

  it('reports 可用 0 upfront for a counting-unit port with no stock (客户原始症状)', async () => {
    const wrapper = mountTable(countingUnitContext);
    await addRow(wrapper);

    // 修前这里什么都不显示, 客户填完点「正式报工」才看到「需要 1只, 可用 0只, 缺少 1只」
    expect(wrapper.find('[data-testid="input-available-stock"]').text()).toBe('可用 0只');
  });

  it('does not merge 只 into 件 when matching batches to the port unit', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [
        batch({ id: 'B-4', materialTypeId: 'RAW-CHICKEN', materialName: '鸡', currentQuantity: 7, quantityUnit: '件', unit: '件' }),
      ],
    });
    const wrapper = mountTable(countingUnitContext);
    await addRow(wrapper);

    // #1976: 计数/包装单位按字面比较 —— 一只 ≠ 一件, 所以 7 件不能算进「只」的可用量
    const text = wrapper.find('[data-testid="input-available-stock"]').text();
    expect(text).toBe('可用 0只 · 另有 7件 单位不同, 未计入');
  });

  it('flags the row red once the entered quantity passes the available stock', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [batch({ id: 'B-1', currentQuantity: 3, quantityUnit: 'kg', unit: 'kg' })],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    const hint = () => wrapper.find('[data-testid="input-available-stock"]');
    expect(hint().classes()).not.toContain('sp-in-stock-over');

    const input = wrapper.find('[data-testid="material-input-total"] input');
    await input.setValue('5');
    await flushPromises();

    expect(hint().classes()).toContain('sp-in-stock-over');
  });

  it('still lets the operator submit when over-drawn — warn, never lock out', async () => {
    // rawBatchOptions 为空/偏小 ≠ 真的没货 (仓库列表取不到、workflow 原料类型过滤对不上
    // 都会置空)。硬闸会让报工彻底提交不了, 比后端报缺料更糟 —— 先做过阻断版, 7 个既有
    // 挂载测试当场变红, 渲染出的「正式报工」确实是 disabled。
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [batch({ id: 'B-1', currentQuantity: 1, quantityUnit: 'kg', unit: 'kg' })],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    // 投料 99kg 远超可用 1kg, 其余字段照常填满
    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 99);
    wrapper.find('[data-testid="output-quantity"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
    await flushPromises();

    expect(wrapper.find('[data-testid="input-available-stock"]').classes()).toContain('sp-in-stock-over');

    await wrapper.findAll('button').find((b) => b.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    // 提示到位, 但提交没有被前端拦住 —— 是否放行由后端 fail-closed 决定
    expect(submitRow).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).not.toContain('超出可领用库存');
  });
});

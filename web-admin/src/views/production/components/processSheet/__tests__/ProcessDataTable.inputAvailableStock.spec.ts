import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';

const getAvailableRawBatches = vi.fn();
const getInputAvailability = vi.fn();
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
    getInputAvailability: (...args: unknown[]) => getInputAvailability(...args),
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
 * 投入行「生产仓可用」的**行为**契约 —— 显示的必须是后端 `input-availability` 返回的那个数。
 *
 * 这组测试守的是历史上真出过的那次事故: 前端自己按批次汇总, 界面「可用 10kg」而提交时后端
 * 「可用 0kg」。所以每个用例都刻意**喂一批会让前端算出别的数的批次**, 再断言界面上出现的
 * 是后端那个数 —— 只断言"显示了个数"是不够的, 自算版同样能显示出个数来。
 *
 * 源码结构侧 (那套自算代码有没有被捡回来) 在同目录 `inputAvailableStock.source.spec.ts`。
 */
describe('投入行「生产仓可用」: 显示后端权威值', () => {
  beforeEach(() => {
    listWarehouses.mockReset();
    getAvailableRawBatches.mockReset();
    getInputAvailability.mockReset();
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
    getInputAvailability.mockResolvedValue({ success: true, data: [] });
  });

  /** 前端若还在自算, 按这批批次会得出 12.5kg —— 恰好是"看着很对"的那种错数字。 */
  function feedMisleadingBatches() {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [
        batch({ id: 'B-1', batchNumber: 'MT-1', currentQuantity: 12, quantityUnit: 'kg', unit: 'kg' }),
        batch({ id: 'B-2', batchNumber: 'MT-2', currentQuantity: 500, quantityUnit: 'g', unit: 'g' }),
      ],
    });
  }

  it('显示后端的 3kg, 而不是照批次自算出来的 12.5kg', async () => {
    feedMisleadingBatches();
    getInputAvailability.mockResolvedValue({
      success: true,
      data: [{ workflowPortId: 'IN-BEEF', materialTypeId: 'RAW-BEEF', available: 3, unit: 'kg', elsewhere: [] }],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="input-available-stock"]').text()).toBe('3kg');
    // 自算版的答案一次都不许出现在页面上
    expect(wrapper.text()).not.toContain('12.5');
  });

  it('请求带上端口身份 (workflowPortId + 物料 + 单位), 否则后端按什么算', async () => {
    const wrapper = mountTable();
    await addRow(wrapper);

    expect(getInputAvailability).toHaveBeenCalled();
    const [factoryId, planId, ports] = getInputAvailability.mock.calls[0];
    expect(factoryId).toBe('F006');
    expect(planId).toBe('PLAN-1');
    expect(ports).toEqual([{ workflowPortId: 'IN-BEEF', materialTypeId: 'RAW-BEEF', unit: 'kg' }]);
  });

  it('可用 0 时标红, 并说清料在哪 —— 「真没货」和「有货但没调过来」得分得开', async () => {
    feedMisleadingBatches();
    getInputAvailability.mockResolvedValue({
      success: true,
      data: [{
        workflowPortId: 'IN-BEEF', materialTypeId: 'RAW-BEEF', available: 0, unit: 'kg',
        elsewhere: [{ warehouseName: '主仓', quantity: 200, unit: 'kg' }],
      }],
    });
    const wrapper = mountTable();
    await addRow(wrapper);

    const stock = wrapper.find('[data-testid="input-available-stock"]');
    expect(stock.text()).toBe('0kg');
    expect(stock.classes()).toContain('sp-in-stock-zero');
    expect(wrapper.find('[data-testid="input-elsewhere-stock"]').text())
      .toBe('主仓另有 200kg，待调拨入生产仓');
  });

  it('后端没这个端口的数 → 什么都不显示, 不猜也不留占位', async () => {
    feedMisleadingBatches();
    getInputAvailability.mockResolvedValue({ success: true, data: [] });
    const wrapper = mountTable();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="input-available-stock"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="input-elsewhere-stock"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('12.5');
  });

  it('接口挂了 → 同样什么都不显示 (禁降级: 不回落到前端自算)', async () => {
    feedMisleadingBatches();
    getInputAvailability.mockRejectedValue(new Error('boom'));
    const wrapper = mountTable();
    await addRow(wrapper);

    expect(wrapper.find('[data-testid="input-available-stock"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('12.5');
  });

  it('刷新拿不到时不留旧值 —— 旧值就是过期库存, 比不显示更糟', async () => {
    feedMisleadingBatches();
    getInputAvailability.mockResolvedValue({
      success: true,
      data: [{ workflowPortId: 'IN-BEEF', materialTypeId: 'RAW-BEEF', available: 3, unit: 'kg', elsewhere: [] }],
    });
    const wrapper = mountTable();
    await addRow(wrapper);
    expect(wrapper.find('[data-testid="input-available-stock"]').text()).toBe('3kg');

    const refresh = async () => {
      // 另一个 tab 保存后父组件会调这个方法重拉三类共享余量
      (wrapper.vm as unknown as { refreshSharedInventories: () => void }).refreshSharedInventories();
      await flushPromises();
    };

    // ① 后端答了但不认账
    getInputAvailability.mockResolvedValue({ success: false, data: null, message: 'nope' });
    await refresh();
    expect(wrapper.find('[data-testid="input-available-stock"]').exists()).toBe(false);

    // ② 直接抛 —— catch 分支同样不许把上一次的 3kg 留在屏幕上
    getInputAvailability.mockResolvedValue({
      success: true,
      data: [{ workflowPortId: 'IN-BEEF', materialTypeId: 'RAW-BEEF', available: 3, unit: 'kg', elsewhere: [] }],
    });
    await refresh();
    expect(wrapper.find('[data-testid="input-available-stock"]').text()).toBe('3kg');

    getInputAvailability.mockRejectedValue(new Error('boom'));
    await refresh();
    expect(wrapper.find('[data-testid="input-available-stock"]').exists()).toBe(false);
  });

  it('填多少都不再被前端标红 (判据是自算值, 判据错了标红也是错的)', async () => {
    feedMisleadingBatches();
    const wrapper = mountTable();
    await addRow(wrapper);

    wrapper.find('[data-testid="material-input-total"]')
      .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 99);
    await flushPromises();

    expect(wrapper.find('.sp-in-stock-over').exists()).toBe(false);
  });

  it('提交路径不受影响 —— 超领仍由后端 fail-closed 兜底', async () => {
    getAvailableRawBatches.mockResolvedValue({
      success: true,
      data: [batch({ id: 'B-1', currentQuantity: 1, quantityUnit: 'kg', unit: 'kg' })],
    });
    getInputAvailability.mockResolvedValue({
      success: true,
      data: [{ workflowPortId: 'IN-BEEF', materialTypeId: 'RAW-BEEF', available: 1, unit: 'kg', elsewhere: [] }],
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

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';

const saveDraftRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: (...args: unknown[]) => saveDraftRow(...args),
    submitRow: vi.fn().mockResolvedValue({ success: true, data: { submissionStatus: 'SUBMITTED' } }),
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

const dirty = (w: ReturnType<typeof mountTable>) => (w.vm as unknown as { hasUnsavedRows: boolean }).hasUnsavedRows;

async function addRow(w: ReturnType<typeof mountTable>) {
  await w.findAll('button').find((b) => b.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

function setInput(w: ReturnType<typeof mountTable>, testid: string, value: number) {
  w.find(`[data-testid="${testid}"]`).findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', value);
}

/**
 * 关窗口该不该拦 —— 客户 2026-07-31:「我已经点击保存草稿了」却还被问「有未保存内容」。
 *
 * 🔴 旧判据是 `rowStatus === 'UNSAVED'`, 两个方向都错, 所以这组测试**两边都钉**:
 *   - 空白新行不该拦 (没东西可丢; 拦多了用户学会闭眼点确认, 这条提醒就废了)
 *   - 保存后又改了要拦 (rowStatus 永远不会从 DRAFT 变回 UNSAVED, 旧判据这里是漏的)
 */
describe('关闭前的未保存拦截 (客户 2026-07-31)', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    saveDraftRow.mockResolvedValue({
      success: true,
      data: { submissionStatus: 'DRAFT', materialized: false },
    });
    vi.restoreAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
  });

  it('刚打开、一行都没有 → 不拦', async () => {
    const wrapper = mountTable();
    await flushPromises();
    expect(dirty(wrapper)).toBe(false);
  });

  it('点了「新增行」但一个字没填 → 不拦 (没有任何东西可丢)', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);
    expect(dirty(wrapper)).toBe(false);
  });

  it('在新行里填了东西 → 拦', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);

    setInput(wrapper, 'material-input-total', 10);
    await flushPromises();
    expect(dirty(wrapper)).toBe(true);
  });

  it('点「保存草稿」之后 → 不再拦 (客户撞到的就是这一步)', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);
    setInput(wrapper, 'material-input-total', 10);
    setInput(wrapper, 'output-quantity', 8);
    await flushPromises();
    expect(dirty(wrapper)).toBe(true);

    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click');
    await flushPromises();

    expect(saveDraftRow).toHaveBeenCalledTimes(1);
    expect(dirty(wrapper)).toBe(false);
  });

  it('保存之后**又改了内容** → 重新拦 (旧判据在这里是漏的)', async () => {
    const wrapper = mountTable();
    await flushPromises();
    await addRow(wrapper);
    setInput(wrapper, 'material-input-total', 10);
    setInput(wrapper, 'output-quantity', 8);
    await flushPromises();
    await wrapper.findAll('button').find((b) => b.text().includes('保存草稿'))!.trigger('click');
    await flushPromises();
    expect(dirty(wrapper)).toBe(false);

    // 存完又把产出从 8 改成 9 —— 这是真正会丢的编辑
    setInput(wrapper, 'output-quantity', 9);
    await flushPromises();
    expect(dirty(wrapper)).toBe(true);
  });
});

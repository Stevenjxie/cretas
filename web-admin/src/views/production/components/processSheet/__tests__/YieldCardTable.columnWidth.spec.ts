/**
 * 出成率总览的列宽记忆 —— 客户 Sheet Row 13:「调好的列宽刷新就没了」。
 *
 * 这里走真实挂载 (不是源码字符串断言), 断的是渲染出来的 <col width>:
 * 只有 composable 真的被接到 `:width` 上、`@header-dragend` 真的被绑上,
 * 这些断言才会绿。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import YieldCardTable from '../YieldCardTable.vue';

const getInventoryYieldCard = vi.fn();

vi.mock('@/api/processSheet', () => ({
  getInventoryYieldCard: (...args: unknown[]) => getInventoryYieldCard(...args),
}));

const STORAGE_KEY_F006 = 'cretas_table_col_width:F006:production.processSheet.yieldCard';
const STORAGE_KEY_LIUSHANMEN = 'cretas_table_col_width:LIUSHANMEN:production.processSheet.yieldCard';

/** 模板里各列的书写顺序 —— <col> 与它一一对应。 */
const COLUMN_ORDER = [
  'processOrder', 'processDate', 'processName', 'batchNumber', 'sourceBatchNumber',
  'feedQuantity', 'sourceConsumedRatio', 'inheritedRawEquivalentQuantity',
  'produced', 'used', 'remaining', 'rowTotalCost', 'unitPrice',
  'stepYieldRate', 'cumulativeYieldRate', 'status',
] as const;

/** 出成率总览默认列宽 —— 与改造前逐像素一致 (min-width 那三列由 min-width 决定)。 */
const DEFAULT_RENDERED_WIDTHS: Record<string, string> = {
  processOrder: '58', processDate: '118', processName: '180', batchNumber: '230',
  sourceBatchNumber: '210', feedQuantity: '110', sourceConsumedRatio: '110',
  inheritedRawEquivalentQuantity: '132', produced: '100', used: '100', remaining: '100',
  rowTotalCost: '116', unitPrice: '105', stepYieldRate: '132', cumulativeYieldRate: '122',
  status: '100',
};

function mountTable(factoryId = 'F006') {
  return mount(YieldCardTable, {
    props: { factoryId, planId: 'PLAN-001' },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

/** 读第一份 colgroup (表头那份) 的宽度, 映射成 prop → width。 */
function renderedWidths(wrapper: ReturnType<typeof mountTable>): Record<string, string | undefined> {
  const cols = wrapper.findAll('col').slice(0, COLUMN_ORDER.length);
  const result: Record<string, string | undefined> = {};
  COLUMN_ORDER.forEach((prop, index) => {
    result[prop] = cols[index]?.attributes('width');
  });
  return result;
}

describe('YieldCardTable 列宽记忆', () => {
  beforeEach(() => {
    getInventoryYieldCard.mockReset();
    getInventoryYieldCard.mockResolvedValue({
      data: [{
        batchNumber: 'CLK-W-A', produced: 10, used: 0, remaining: 10,
        status: 'ACTIVE', unit: 'kg', unitPrice: 1, rowTotalCost: 10,
        stepYieldRate: 100, cumulativeYieldRate: 100,
      }],
    });
    localStorage.clear();
  });

  it('没存过时每一列都保持改造前的默认宽度', async () => {
    const wrapper = mountTable();
    await flushPromises();

    expect(renderedWidths(wrapper)).toEqual(DEFAULT_RENDERED_WIDTHS);
    // 没记忆就不该出现「恢复默认列宽」入口
    expect(wrapper.find('.yield-card-reset-widths').exists()).toBe(false);
  });

  it('存过的列宽在挂载时被读回来, 其余列不受影响 (客户: 刷新就没了)', async () => {
    localStorage.setItem(STORAGE_KEY_F006, JSON.stringify({ batchNumber: 444, status: 333 }));

    const wrapper = mountTable();
    await flushPromises();

    const widths = renderedWidths(wrapper);
    expect(widths.batchNumber).toBe('444');
    expect(widths.status).toBe('333');
    expect(widths.produced).toBe(DEFAULT_RENDERED_WIDTHS.produced);
    expect(widths.processOrder).toBe(DEFAULT_RENDERED_WIDTHS.processOrder);
    expect(wrapper.find('.yield-card-reset-widths').exists()).toBe(true);
  });

  it('拖完表头就落库, 重新挂载 (=刷新) 还在', async () => {
    const wrapper = mountTable();
    await flushPromises();

    const table = wrapper.findComponent({ name: 'ElTable' });
    table.vm.$emit('header-dragend', 260, 100, { property: 'produced' });
    await nextTick();

    expect(JSON.parse(localStorage.getItem(STORAGE_KEY_F006) || 'null')).toEqual({ produced: 260 });
    expect(renderedWidths(wrapper).produced).toBe('260');

    wrapper.unmount();
    const reopened = mountTable();
    await flushPromises();
    expect(renderedWidths(reopened).produced).toBe('260');
  });

  it('按租户隔离 —— 另一个工厂看不到 F006 拖出来的宽度', async () => {
    localStorage.setItem(STORAGE_KEY_F006, JSON.stringify({ produced: 260 }));
    localStorage.setItem(STORAGE_KEY_LIUSHANMEN, JSON.stringify({ produced: 400 }));

    const f006 = mountTable('F006');
    await flushPromises();
    expect(renderedWidths(f006).produced).toBe('260');

    const liushanmen = mountTable('LIUSHANMEN');
    await flushPromises();
    expect(renderedWidths(liushanmen).produced).toBe('400');
  });

  it('存储内容损坏时照常渲染默认宽度, 不抛错', async () => {
    localStorage.setItem(STORAGE_KEY_F006, '{坏掉的内容');

    const wrapper = mountTable();
    await flushPromises();

    expect(renderedWidths(wrapper)).toEqual(DEFAULT_RENDERED_WIDTHS);
  });

  it('「恢复默认列宽」把记忆清干净并退回默认宽度', async () => {
    localStorage.setItem(STORAGE_KEY_F006, JSON.stringify({ produced: 260 }));

    const wrapper = mountTable();
    await flushPromises();
    expect(renderedWidths(wrapper).produced).toBe('260');

    await wrapper.find('.yield-card-reset-widths').trigger('click');
    await nextTick();

    expect(localStorage.getItem(STORAGE_KEY_F006)).toBeNull();
    expect(renderedWidths(wrapper).produced).toBe(DEFAULT_RENDERED_WIDTHS.produced);
    expect(wrapper.find('.yield-card-reset-widths').exists()).toBe(false);
  });
});

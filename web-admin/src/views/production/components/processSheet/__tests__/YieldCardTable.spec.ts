import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import YieldCardTable from '../YieldCardTable.vue';

const getInventoryYieldCard = vi.fn();

vi.mock('@/api/processSheet', () => ({
  getInventoryYieldCard: (...args: unknown[]) => getInventoryYieldCard(...args),
}));

describe('YieldCardTable', () => {
  beforeEach(() => {
    getInventoryYieldCard.mockReset();
  });

  it('renders upstream provenance values used to explain cumulative yield', async () => {
    getInventoryYieldCard.mockResolvedValue({
      data: [
        {
          batchNumber: 'CLK-W-BLANCH',
          produced: 604.5,
          used: 0,
          remaining: 604.5,
          status: 'ACTIVE',
          unit: 'kg',
          rowTotalCost: 15112.5,
          unitPrice: 25,
          inputQuantity: 765.19,
          sourceBatchNumber: 'CLK-W-ROLL',
          feedQuantity: 765.19,
          sourceProducedQuantity: 1571.19,
          sourceConsumedRatio: 48.7013,
          inheritedRawEquivalentQuantity: 701.2988,
          inheritedCost: 15303.8,
          addedCost: -191.3,
          stepYieldRate: 79,
          cumulativeYieldRate: 86.1972,
        },
        {
          batchNumber: 'CLK-W-MIX',
          produced: 80,
          used: 0,
          remaining: 80,
          status: 'ACTIVE',
          unit: 'kg',
          rowTotalCost: 2000,
          unitPrice: 25,
          inputQuantity: 90,
          sourceBatchNumber: 'CLK-W-A, CLK-W-B',
          feedQuantity: 90,
          sourceProducedQuantity: 135,
          sourceConsumedRatio: 66.6667,
          inheritedRawEquivalentQuantity: 100,
          inheritedCost: 1350,
          addedCost: 650,
          stepYieldRate: 88.8889,
          cumulativeYieldRate: 80,
        },
      ],
    });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('CLK-W-ROLL');
    expect(text).toContain('765.19');
    expect(text).toContain('48.70%');
    expect(text).toContain('701.30');
    expect(text).toContain('86.20%');
    expect(text).toContain('CLK-W-A, CLK-W-B');
    expect(text).toContain('66.67%');
    expect(text).toContain('100.00');
    expect(text).toContain('80.00%');
  });

  it('explains missing yield and cost values instead of leaving only dashes', async () => {
    getInventoryYieldCard.mockResolvedValue({
      data: [
        {
          batchNumber: 'CLK-W-MISSING',
          processName: 'mix',
          produced: 25,
          used: 0,
          remaining: 25,
          status: 'ACTIVE',
          unit: 'kg',
          inputQuantity: 30,
          rowTotalCost: null,
          unitPrice: null,
          sourceBatchNumber: null,
          feedQuantity: null,
          sourceConsumedRatio: null,
          inheritedRawEquivalentQuantity: null,
          stepYieldRate: 83.3333,
          cumulativeYieldRate: null,
        },
      ],
    });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('有 1 行累计出成率未显示');
    expect(text).toContain('检查来源批次、原料投入或 SKU 标准克重配置');
    expect(text).toContain('有 1 行成本未显示');
  });

  it('explains a genuinely empty plan-wide yield card', async () => {
    getInventoryYieldCard.mockResolvedValue({ data: [] });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('\u6682\u65e0\u534a\u6210\u54c1\u5e93\u5b58');
    expect(text).toContain('\u4fdd\u5b58\u4efb\u4e00\u5de5\u5e8f\u6709\u6548\u4ea7\u51fa\u540e\u4f1a\u751f\u6210\u5168\u5de5\u5e8f\u51fa\u6210\u7387\u6c47\u603b');
  });

  it('shows a load failure message instead of silently rendering empty data', async () => {
    getInventoryYieldCard.mockRejectedValue(new Error('network down'));

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('出成率数据加载失败');
    expect(text).toContain('请刷新重试');
  });
});

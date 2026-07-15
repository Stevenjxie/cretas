import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import InventoryTable from '../InventoryTable.vue';

const getInventory = vi.fn();

vi.mock('@/api/processSheet', () => ({
  getInventory: (...args: unknown[]) => getInventory(...args),
}));

describe('InventoryTable', () => {
  beforeEach(() => {
    getInventory.mockReset();
  });

  it('explains a genuinely empty WIP inventory instead of only showing a blank table', async () => {
    getInventory.mockResolvedValue({ data: [] });

    const wrapper = mount(InventoryTable, {
      props: {
        factoryId: 'F006',
        planId: 'PLAN-001',
        processCode: 'chaoshui',
        processOrder: 3,
      },
      global: {
        plugins: [ElementPlus],
        stubs: { teleport: true, transition: false },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('\u6682\u65e0\u534a\u6210\u54c1\u5e93\u5b58');
    expect(text).toContain('\u4fdd\u5b58\u5f53\u524d\u5de5\u5e8f\u6709\u6548\u4ea7\u51fa\u540e\u4f1a\u751f\u6210\u6279\u6b21\u5e93\u5b58');
  });

  it('shows a retryable error state when WIP inventory loading fails', async () => {
    getInventory
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({
        data: [
          {
            batchNumber: 'CLK-W-RECOVERED',
            produced: 10,
            used: 0,
            remaining: 10,
            unitPrice: 12.5,
            status: 'ACTIVE',
          },
        ],
      });

    const wrapper = mount(InventoryTable, {
      props: {
        factoryId: 'F006',
        planId: 'PLAN-001',
        processCode: 'chaoshui',
        processOrder: 3,
      },
      global: {
        plugins: [ElementPlus],
        stubs: { teleport: true, transition: false },
      },
    });

    await flushPromises();
    await nextTick();

    expect(wrapper.text()).toContain('\u534a\u6210\u54c1\u5e93\u5b58\u52a0\u8f7d\u5931\u8d25');
    expect(wrapper.text()).toContain('\u8bf7\u5237\u65b0\u91cd\u8bd5');

    await wrapper.find('button').trigger('click');
    await flushPromises();
    await nextTick();

    expect(getInventory).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('CLK-W-RECOVERED');
    expect(wrapper.text()).not.toContain('\u534a\u6210\u54c1\u5e93\u5b58\u52a0\u8f7d\u5931\u8d25');
  });

  it('converts legacy gram inventory snapshots to kg for display', async () => {
    getInventory.mockResolvedValue({ data: [{
      batchNumber: 'CLK-W-GRAMS', produced: 100_000, used: 50_000, remaining: 50_000,
      unit: 'g', unitPrice: 0.1, status: 'ACTIVE',
    }] });
    const wrapper = mount(InventoryTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001', processCode: 'mix', processOrder: 1 },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
    await flushPromises();
    await nextTick();

    expect(wrapper.text()).toContain('CLK-W-GRAMS');
    expect(wrapper.text()).toContain('100');
    expect(wrapper.text()).not.toContain('100000');
  });
});

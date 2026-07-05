// Bug 1 regression coverage (fix/process-entry-cache-and-blend-cost, F006 现场走查):
// 半成品(SFI)/成品(FG) 库存余量是全局共享的常驻库存 (可被任意后续工序 tab 选作投料来源),
// 但 ProcessDataTable 只在挂载 / (factoryId, processCode, productTypeId) 变化时加载一次
// (见组件内 watch)。逐工序对话框内各 process tab 长期挂载不销毁 → 这三份 key 之后永不再变,
// 于是另一 tab 保存一行改变了 SFI/FG 余量后, 本 tab 的下拉/校验仍读旧值, 造成假阳性
// "用量超出半成品库存余" 拦截 (真实 Postgres 数据从未错, 纯前端缓存未失效)。
//
// 修复: ProcessDataTable 新增 refreshSharedInventories() (并入既有 defineExpose), 由
// ProcessSheet.vue 在任一 tab 保存后对全部 tab 调用。这里直接验证该导出方法确实重新拉取
// SFI/FG 库存 (以及 isXiuYou 场景下的原料批次), 不清空/不用等 (factoryId, processCode,
// productTypeId) 再变一次。
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const getSemiFinishedInventory = vi.fn();
const getFinishedGoodsInventory = vi.fn();
const getAvailableRawBatches = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    getSemiFinishedInventory: (...args: unknown[]) => getSemiFinishedInventory(...args),
    getFinishedGoodsInventory: (...args: unknown[]) => getFinishedGoodsInventory(...args),
    getAvailableRawBatches: (...args: unknown[]) => getAvailableRawBatches(...args),
    saveRow: vi.fn(),
    deleteRow: vi.fn(),
    getRowHistory: vi.fn().mockResolvedValue({ success: true, data: [] }),
  };
});

const listWarehouses = vi.fn();
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: (...args: unknown[]) => listWarehouses(...args),
}));

import ProcessDataTable from '../ProcessDataTable.vue';

function mountShuZhiTable() {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006',
      planId: 'PLAN-1',
      processCode: 'shuzhi', // 熟制 (multi-source 混锅) → showSfi=showFg=true
      processOrder: 2,
      processLabel: '熟制',
      productTypeId: 'PT-1',
      upstreamItems: [],
      ownInventoryItems: [],
      initialRows: [],
      viewMode: 'grid',
    },
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, transition: false },
    },
  });
}

describe('ProcessDataTable.refreshSharedInventories (Bug 1: cross-tab SFI/FG cache invalidation)', () => {
  beforeEach(() => {
    getSemiFinishedInventory.mockReset();
    getFinishedGoodsInventory.mockReset();
    getAvailableRawBatches.mockReset();
    getSemiFinishedInventory.mockResolvedValue({
      success: true,
      data: [{ intermediateBatchNo: 'SFI-A', availableQuantity: 1.05, unit: 'kg' }],
    });
    getFinishedGoodsInventory.mockResolvedValue({ success: true, data: [] });
    getAvailableRawBatches.mockResolvedValue({ success: true, data: [] });
  });

  it('mounts and loads SFI + FG inventory exactly once (baseline)', async () => {
    mountShuZhiTable();
    await flushPromises();

    expect(getSemiFinishedInventory).toHaveBeenCalledTimes(1);
    expect(getFinishedGoodsInventory).toHaveBeenCalledTimes(1);
  });

  it('does NOT re-fetch SFI/FG on its own without an explicit trigger (this is exactly the stale-cache bug: props unchanged → no re-fetch)', async () => {
    mountShuZhiTable();
    await flushPromises();
    getSemiFinishedInventory.mockClear();
    getFinishedGoodsInventory.mockClear();

    // Simulate time passing / another tab saving a row — no prop change on THIS
    // component, so absent an explicit call, it must never re-fetch on its own.
    await flushPromises();

    expect(getSemiFinishedInventory).not.toHaveBeenCalled();
    expect(getFinishedGoodsInventory).not.toHaveBeenCalled();
  });

  it('exposes refreshSharedInventories() which re-fetches SFI + FG inventory on demand (the fix)', async () => {
    const wrapper = mountShuZhiTable();
    await flushPromises();
    getSemiFinishedInventory.mockClear();
    getFinishedGoodsInventory.mockClear();

    // Simulate the SFI anchor balance having been pushed 1.05kg → 2.03kg by another
    // process tab's save (e.g. 焯水's postSfiOutput materializing new output into the
    // shared anchor this tab's dropdown reads from).
    getSemiFinishedInventory.mockResolvedValue({
      success: true,
      data: [{ intermediateBatchNo: 'SFI-A', availableQuantity: 2.03, unit: 'kg' }],
    });

    // This is exactly what ProcessSheet.vue's onRowSaved calls on every tab after ANY
    // row save — props (factoryId/processCode/productTypeId) are unchanged, so without
    // this explicit method the component would never re-fetch (see previous test).
    const vm = wrapper.vm as unknown as { refreshSharedInventories: () => void };
    expect(typeof vm.refreshSharedInventories).toBe('function');
    vm.refreshSharedInventories();
    await flushPromises();

    expect(getSemiFinishedInventory).toHaveBeenCalledTimes(1);
    expect(getFinishedGoodsInventory).toHaveBeenCalledTimes(1);
  });

  it('still exposes hasUnsavedRows alongside refreshSharedInventories (single defineExpose call, no clobbering)', async () => {
    const wrapper = mountShuZhiTable();
    await flushPromises();
    const vm = wrapper.vm as unknown as { hasUnsavedRows: unknown; refreshSharedInventories: unknown };
    expect(vm.hasUnsavedRows).toBe(false);
    expect(typeof vm.refreshSharedInventories).toBe('function');
  });
});

describe('ProcessDataTable.refreshSharedInventories — 修油(xiuyou) also refreshes raw material batches', () => {
  beforeEach(() => {
    getSemiFinishedInventory.mockReset();
    getFinishedGoodsInventory.mockReset();
    getAvailableRawBatches.mockReset();
    listWarehouses.mockReset();
    getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
    getFinishedGoodsInventory.mockResolvedValue({ success: true, data: [] });
    getAvailableRawBatches.mockResolvedValue({ success: true, data: [] });
    listWarehouses.mockResolvedValue({
      success: true,
      data: [{ id: 'WH-1', code: 'WH-LOG', type: 'RAW', isActive: true }],
    });
  });

  it('refreshSharedInventories() re-fetches raw batch options for 修油 (isXiuYou)', async () => {
    const wrapper = mount(ProcessDataTable, {
      props: {
        factoryId: 'F006',
        planId: 'PLAN-1',
        processCode: 'xiuyou',
        processOrder: 1,
        processLabel: '修油',
        productTypeId: 'PT-1',
        upstreamItems: [],
        ownInventoryItems: [],
        initialRows: [],
        viewMode: 'grid',
      },
      global: {
        plugins: [ElementPlus],
        stubs: { teleport: true, transition: false },
      },
    });
    await flushPromises();
    expect(getAvailableRawBatches).toHaveBeenCalledTimes(1);
    getAvailableRawBatches.mockClear();

    (wrapper.vm as unknown as { refreshSharedInventories: () => void }).refreshSharedInventories();
    await flushPromises();

    expect(getAvailableRawBatches).toHaveBeenCalledTimes(1);
  });
});

// 2B Task F2 regression coverage (product-process-workflow runtime 2B, clerk-sheet):
//
// ProcessDataTable.vue renders a read-only "planned output / required inputs" banner whenever
// ProcessSheet.vue threads a non-null `workflowContext` prop (workflow-activated plan). Legacy
// plans never receive this prop (stays null via the component's own default), so the banner —
// and everything else on the row — must render exactly as before (fool-proof-design.md Rule
// 2/3: tell the clerk what to produce, read-only; no change to saveRow's request shape).
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

function mountTable(workflowContext: unknown) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006',
      planId: 'PLAN-1',
      processCode: 'shuzhi',
      processOrder: 2,
      processLabel: '熟制',
      allowMultipleUpstreamSources: true,
      productTypeId: 'PT-1',
      upstreamItems: [],
      ownInventoryItems: [],
      initialRows: [],
      viewMode: 'card',
      workflowContext,
    },
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, transition: false },
    },
  });
}

describe('ProcessDataTable.vue workflow-planned-output banner (2B Task F2)', () => {
  beforeEach(() => {
    getSemiFinishedInventory.mockReset();
    getFinishedGoodsInventory.mockReset();
    getAvailableRawBatches.mockReset();
    getSemiFinishedInventory.mockResolvedValue({ success: true, data: [] });
    getFinishedGoodsInventory.mockResolvedValue({ success: true, data: [] });
    getAvailableRawBatches.mockResolvedValue({ success: true, data: [] });
  });

  it('shows the workflow-planned output (name/unit/成品-半成品 tag) and required raw input hint read-only, when workflowContext is present', async () => {
    const wrapper = mountTable({
      workflowNodeId: 'N1',
      workProcessId: 'WP1',
      processName: '熟制',
      defaultCostCategory: 'SEASONING',
      processOrder: 2,
      plannedUnit: 'kg',
      allowMultipleUpstreamSources: true,
      allowFinishedGoodsSource: false,
      customFieldSchema: null,
      inputs: [
        {
          workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1',
          materialName: '卤水调料', unit: 'kg', required: true, skuResolved: true, finished: false,
        },
      ],
      output: {
        workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
        materialName: '卤猪蹄成品', unit: 'kg', required: true, skuResolved: true, finished: true,
      },
    });
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain('计划产出');
    expect(text).toContain('卤猪蹄成品');
    expect(text).toContain('kg');
    expect(text).toContain('成品');
    expect(text).toContain('需要原料');
    expect(text).toContain('卤水调料');
    // No stale/deleted-SKU warning when skuResolved is true.
    expect(text).not.toContain('已失效');
  });

  it('shows the "SKU 已失效" fool-proof warning (not a crash) when the planned output SKU has been deleted', async () => {
    const wrapper = mountTable({
      workflowNodeId: 'N1',
      workProcessId: 'WP1',
      processName: '熟制',
      defaultCostCategory: 'SEASONING',
      processOrder: 2,
      plannedUnit: 'kg',
      allowMultipleUpstreamSources: true,
      allowFinishedGoodsSource: false,
      customFieldSchema: null,
      inputs: [],
      output: {
        workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-DELETED',
        materialName: null, unit: null, required: true, skuResolved: false, finished: true,
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('SKU 已失效');
    expect(wrapper.text()).toContain('请回 Workflow 配置');
  });

  it('renders no workflow banner at all for legacy processes (workflowContext null/default) — zero regression', async () => {
    const wrapper = mountTable(null);
    await flushPromises();

    const text = wrapper.text();
    expect(text).not.toContain('计划产出');
    expect(text).not.toContain('需要原料');
    expect(text).not.toContain('SKU 已失效');
  });
});

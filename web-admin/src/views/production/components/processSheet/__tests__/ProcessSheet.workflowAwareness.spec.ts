// 2B Task F1/F2 regression coverage (product-process-workflow runtime 2B, clerk-sheet):
//
// ProcessSheet.vue's resolveProcesses() now probes GET .../process-sheet/workflow-config
// FIRST. A non-null config (workflow-activated plan) builds tabs from the immutable workflow
// snapshot (ProcessDescriptor[]) instead of the legacy ProductWorkProcess path, and threads the
// planned-output/required-input port context through to ProcessDataTable as `workflowContext`
// (F2, only used for read-only display there — no change to saveRow's request shape).
//
// A null config (legacy plan, the overwhelming majority today) must fall through to the
// existing `getProductWorkProcesses` path byte-for-byte unchanged. These two branches are what
// this spec proves; ProcessDataTable itself is stubbed so this spec stays scoped to
// ProcessSheet.vue's branch-selection logic and does not re-test ProcessDataTable's internals.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const getInventory = vi.fn();
const getRows = vi.fn();
const getWorkflowSheetConfig = vi.fn();

vi.mock('@/api/processSheet', () => ({
  getInventory: (...args: unknown[]) => getInventory(...args),
  getRows: (...args: unknown[]) => getRows(...args),
  getWorkflowSheetConfig: (...args: unknown[]) => getWorkflowSheetConfig(...args),
}));

const getProductWorkProcesses = vi.fn();
vi.mock('@/api/processProduction', () => ({
  getProductWorkProcesses: (...args: unknown[]) => getProductWorkProcesses(...args),
}));

import ProcessSheet from '../ProcessSheet.vue';

/** Minimal stub that records every prop ProcessSheet.vue passes down, so this spec can assert
 * on the derived ProcEntry shape without depending on ProcessDataTable's own internals/API calls. */
const ProcessDataTableStub = {
  name: 'ProcessDataTable',
  props: [
    'factoryId', 'planId', 'processCode', 'processOrder', 'productTypeId', 'processLabel',
    'allowSemiFinishedInjection', 'allowMultipleUpstreamSources', 'isFirstProcess',
    'customFieldSchema', 'allowFinishedGoodsSource', 'workflowContext',
    'upstreamProcessLabel', 'upstreamItems', 'ownInventoryItems', 'initialRows', 'viewMode',
  ],
  template: '<div class="stub-process-data-table" />',
};

function mountSheet() {
  return mount(ProcessSheet, {
    props: {
      factoryId: 'F006',
      planId: 'PLAN-WF-1',
      productTypeId: 'PT-1',
      productName: '猪蹄',
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        ProcessDataTable: ProcessDataTableStub,
        InventoryTable: true,
        YieldCardTable: true,
        teleport: true,
        transition: false,
      },
    },
  });
}

describe('ProcessSheet.vue workflow-awareness (2B Task F1/F2)', () => {
  beforeEach(() => {
    getInventory.mockReset();
    getRows.mockReset();
    getWorkflowSheetConfig.mockReset();
    getProductWorkProcesses.mockReset();
    getInventory.mockResolvedValue({ success: true, data: [] });
    getRows.mockResolvedValue({ success: true, data: [] });
  });

  it('workflow config present: builds tabs from the workflow snapshot with planned-output threaded to ProcessDataTable, and never calls the legacy endpoint', async () => {
    getWorkflowSheetConfig.mockResolvedValue({
      success: true,
      data: {
        workflowBatchId: 100,
        workflowInstanceId: 200,
        productTypeId: 'PT-1',
        processes: [
          {
            workflowNodeId: 'N1',
            workProcessId: 'WP1',
            processName: '修油',
            defaultCostCategory: 'RAW_MATERIAL',
            processOrder: 1,
            plannedUnit: 'kg',
            allowMultipleUpstreamSources: false,
            allowFinishedGoodsSource: false,
            customFieldSchema: null,
            inputs: [
              {
                workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1',
                materialName: '猪蹄', unit: 'kg', required: true, skuResolved: true, finished: false,
              },
            ],
            output: {
              workflowPortId: 'OUT1', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1',
              materialName: '修油半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
            },
          },
          {
            workflowNodeId: 'N2',
            workProcessId: 'WP2',
            processName: '气调包装',
            defaultCostCategory: 'PACKAGING',
            processOrder: 2,
            plannedUnit: '盒',
            allowMultipleUpstreamSources: false,
            allowFinishedGoodsSource: false,
            customFieldSchema: null,
            inputs: [],
            output: {
              workflowPortId: 'OUT2', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
              // Deleted/unresolved SKU: materialName/unit null, skuResolved false — must not crash.
              materialName: null, unit: null, required: true, skuResolved: false, finished: true,
            },
          },
        ],
      },
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getWorkflowSheetConfig).toHaveBeenCalledWith('F006', 'PLAN-WF-1');
    expect(getProductWorkProcesses).not.toHaveBeenCalled();

    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(2);

    // Role mode: defaultCostCategory RAW_MATERIAL -> 'xiuyou' archetype, PACKAGING -> 'qidiao'.
    const p1 = tables[0].props();
    expect(p1.processCode).toBe('xiuyou');
    expect(p1.processOrder).toBe(1);
    expect(p1.processLabel).toBe('修油');
    expect(p1.isFirstProcess).toBe(true);
    expect(p1.workflowContext).toBeTruthy();
    expect((p1.workflowContext as { output: { materialName: string } }).output.materialName).toBe('修油半成品');

    const p2 = tables[1].props();
    expect(p2.processCode).toBe('qidiao');
    expect(p2.processOrder).toBe(2);
    expect(p2.processLabel).toBe('气调包装');
    expect(p2.isFirstProcess).toBe(false);
    const p2Output = (p2.workflowContext as { output: { skuResolved: boolean; materialName: string | null } }).output;
    expect(p2Output.skuResolved).toBe(false);
    expect(p2Output.materialName).toBeNull();

    // Inventory/rows still get fetched per-tab keyed off the derived archetype code.
    expect(getInventory).toHaveBeenCalledWith('F006', 'PLAN-WF-1', 'xiuyou', 1);
    expect(getInventory).toHaveBeenCalledWith('F006', 'PLAN-WF-1', 'qidiao', 2);

    // The banner text (planned-output display, F2) is threaded into the stub's props, which
    // is what the real ProcessDataTable.vue renders — see its own component-level assertions
    // in ProcessDataTable.sharedInventoryRefresh.spec.ts sibling-style tests for the render path.
  });

  it('null config (legacy plan): falls through to getProductWorkProcesses unchanged, ProcessDataTable gets no workflow context', async () => {
    getWorkflowSheetConfig.mockResolvedValue({ success: true, data: null });
    getProductWorkProcesses.mockResolvedValue({
      success: true,
      data: [
        {
          id: 1, productTypeId: 'PT-1', workProcessId: 'WP-L1', processOrder: 1,
          unitOverride: null, estimatedMinutesOverride: null, processName: '修油',
          processCategory: 'RAW_MATERIAL', defaultUnit: 'kg', defaultEstimatedMinutes: null,
          defaultCostCategory: null, allowSemiFinishedInjection: false,
          allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
          customFieldSchema: null,
        },
        {
          id: 2, productTypeId: 'PT-1', workProcessId: 'WP-L2', processOrder: 2,
          unitOverride: null, estimatedMinutesOverride: null, processName: '焯水',
          processCategory: 'PROCESSING', defaultUnit: 'kg', defaultEstimatedMinutes: null,
          defaultCostCategory: null, allowSemiFinishedInjection: false,
          allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
          customFieldSchema: null,
        },
      ],
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getWorkflowSheetConfig).toHaveBeenCalledWith('F006', 'PLAN-WF-1');
    expect(getProductWorkProcesses).toHaveBeenCalledWith('F006', 'PT-1');

    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(2);
    expect(tables[0].props().processCode).toBe('xiuyou');
    expect(tables[0].props().processLabel).toBe('修油');
    expect(tables[0].props().workflowContext).toBeNull();
    expect(tables[1].props().processCode).toBe('chaoshui');
    expect(tables[1].props().workflowContext).toBeNull();
  });

  it('workflow-config probe throws: still falls through to the legacy path (defensive, matches existing try/catch pattern)', async () => {
    getWorkflowSheetConfig.mockRejectedValue(new Error('network down'));
    getProductWorkProcesses.mockResolvedValue({
      success: true,
      data: [
        {
          id: 1, productTypeId: 'PT-1', workProcessId: 'WP-L1', processOrder: 1,
          unitOverride: null, estimatedMinutesOverride: null, processName: '修油',
          processCategory: 'RAW_MATERIAL', defaultUnit: 'kg', defaultEstimatedMinutes: null,
          defaultCostCategory: null, allowSemiFinishedInjection: false,
          allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
          customFieldSchema: null,
        },
      ],
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getProductWorkProcesses).toHaveBeenCalledWith('F006', 'PT-1');
    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(1);
    expect(tables[0].props().workflowContext).toBeNull();
  });
});

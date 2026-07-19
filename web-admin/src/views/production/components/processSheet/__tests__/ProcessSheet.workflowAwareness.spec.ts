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

const getSeasoningByProduct = vi.fn();
vi.mock('@/api/bom', () => ({
  bomSeasoningApi: {
    getByProduct: (...args: unknown[]) => getSeasoningByProduct(...args),
  },
}));

import ProcessSheet from '../ProcessSheet.vue';

/** Minimal stub that records every prop ProcessSheet.vue passes down, so this spec can assert
 * on the derived ProcEntry shape without depending on ProcessDataTable's own internals/API calls. */
const ProcessDataTableStub = {
  name: 'ProcessDataTable',
  props: [
    'factoryId', 'planId', 'processCode', 'processOrder', 'productTypeId', 'processLabel',
    'allowSemiFinishedInjection', 'allowMultipleUpstreamSources', 'isFirstProcess',
    'customFieldSchema', 'allowFinishedGoodsSource', 'workflowContext', 'inputUnit', 'outputUnit',
    'upstreamProcessLabel', 'upstreamItems', 'ownInventoryItems', 'initialRows', 'viewMode',
    'seasoningPotEnabled',
    'seasoningConfigured',
  ],
  template: '<div class="stub-process-data-table" />',
};

function mountSheet(plan: { plannedQuantity?: number; plannedUnit?: string | null } = {}) {
  return mount(ProcessSheet, {
    props: {
      factoryId: 'F006',
      planId: 'PLAN-WF-1',
      productTypeId: 'PT-1',
      productName: '猪蹄',
      ...plan,
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
    getSeasoningByProduct.mockReset();
    getInventory.mockResolvedValue({ success: true, data: [] });
    getRows.mockResolvedValue({ success: true, data: [] });
    getSeasoningByProduct.mockResolvedValue({
      success: true,
      data: { injectionConfigs: [], seasoningItems: [] },
    });
  });

  it('maps explicit seasoning pot configuration by workProcessId instead of process category/name', async () => {
    getWorkflowSheetConfig.mockResolvedValue({ success: true, data: null });
    getProductWorkProcesses.mockResolvedValue({
      success: true,
      data: [
        {
          id: 1, productTypeId: 'PT-1', workProcessId: 'WP-CONFIGURED', processOrder: 1,
          unitOverride: null, processName: '普通混料', processCategory: 'PROCESSING',
          defaultUnit: 'kg', defaultOutputUnit: 'kg', defaultCostCategory: null,
          allowSemiFinishedInjection: false, allowMultipleUpstreamSources: false,
          allowFinishedGoodsSource: false, customFieldSchema: null,
        },
        {
          id: 2, productTypeId: 'PT-1', workProcessId: 'WP-UNCONFIGURED', processOrder: 2,
          unitOverride: null, processName: '熟制', processCategory: '熟制',
          defaultUnit: 'kg', defaultOutputUnit: 'kg', defaultCostCategory: null,
          allowSemiFinishedInjection: false, allowMultipleUpstreamSources: false,
          allowFinishedGoodsSource: false, customFieldSchema: null,
        },
      ],
    });
    getSeasoningByProduct.mockResolvedValue({
      success: true,
      data: {
        seasoningItems: [{ workProcessId: 'WP-CONFIGURED', subsequentPotRatio: 0.5 }],
        injectionConfigs: [],
      },
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getSeasoningByProduct).toHaveBeenCalledWith('F006', 'PT-1');
    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(2);
    expect(tables[0].props().seasoningPotEnabled).toBe(true);
    expect(tables[0].props().seasoningConfigured).toBe(true);
    expect(tables[1].props().seasoningPotEnabled).toBe(false);
    expect(tables[1].props().seasoningConfigured).toBe(false);
  });

  it('treats a missing BOM seasoning config as no pot config but blocks and retries other failures', async () => {
    getWorkflowSheetConfig.mockResolvedValue({ success: true, data: null });
    getProductWorkProcesses.mockResolvedValue({
      success: true,
      data: [{
        id: 1, productTypeId: 'PT-1', workProcessId: 'WP-1', processOrder: 1,
        unitOverride: null, processName: '领料', processCategory: 'RAW_MATERIAL',
        defaultUnit: 'kg', defaultOutputUnit: 'kg', defaultCostCategory: null,
        allowSemiFinishedInjection: false, allowMultipleUpstreamSources: false,
        allowFinishedGoodsSource: false, customFieldSchema: null,
      }],
    });
    getSeasoningByProduct.mockRejectedValueOnce(Object.assign(new Error('missing'), { status: 404 }));

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();
    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(1);
    expect(wrapper.findComponent(ProcessDataTableStub).props().seasoningPotEnabled).toBe(false);
    expect(wrapper.findComponent(ProcessDataTableStub).props().seasoningConfigured).toBe(false);

    getSeasoningByProduct.mockRejectedValueOnce(new Error('调料服务不可用'));
    // First load succeeded, so trigger a prop change to force the non-404 branch.
    await wrapper.setProps({ productTypeId: 'PT-2' });
    await flushPromises();
    await flushPromises();
    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(0);
    expect(wrapper.text()).toContain('调料服务不可用');
    expect(wrapper.text()).toContain('重试');

    getSeasoningByProduct.mockResolvedValueOnce({
      success: true,
      data: { injectionConfigs: [], seasoningItems: [] },
    });
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试'));
    if (!retry) throw new Error('找不到重试按钮');
    await retry.trigger('click');
    await flushPromises();
    await flushPromises();
    expect(getSeasoningByProduct).toHaveBeenCalledTimes(3);
    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(1);
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
            plannedUnit: '计划单位不应生效',
            allowMultipleUpstreamSources: false,
            allowFinishedGoodsSource: false,
            customFieldSchema: null,
            inputs: [
              {
                workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1',
                materialName: '猪蹄', unit: 'g', required: true, skuResolved: true, finished: false,
              },
            ],
            output: {
              workflowPortId: 'OUT1', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1',
              materialName: '修油半成品', unit: 'g', required: true, skuResolved: true, finished: false,
            },
            outputs: [{
              workflowPortId: 'OUT1', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1',
              materialName: '修油半成品', unit: 'g', required: true, skuResolved: true, finished: false,
            }],
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
            inputs: [{
              workflowPortId: 'IN2', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1',
              materialName: '修油半成品', unit: 'g', required: true, skuResolved: true, finished: false,
            }],
            output: {
              workflowPortId: 'OUT2', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
              materialName: '气调成品', unit: '盒', gramsPerUnit: 200, required: true, skuResolved: true, finished: true,
            },
            outputs: [{
              workflowPortId: 'OUT2', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
              materialName: '气调成品', unit: '盒', gramsPerUnit: 200, required: true, skuResolved: true, finished: true,
            }],
          },
        ],
      },
    });

    const wrapper = mountSheet({ plannedQuantity: 100_000, plannedUnit: 'g' });
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
    expect(p1.inputUnit).toBe('kg');
    expect(p1.outputUnit).toBe('kg');
    expect((p1.workflowContext as { output: { materialName: string } }).output.materialName).toBe('修油半成品');

    const p2 = tables[1].props();
    expect(p2.processCode).toBe('qidiao');
    expect(p2.processOrder).toBe(2);
    expect(p2.processLabel).toBe('气调包装');
    expect(p2.isFirstProcess).toBe(false);
    const p2Output = (p2.workflowContext as { output: { skuResolved: boolean; materialName: string | null } }).output;
    expect(p2Output.skuResolved).toBe(true);
    expect(p2Output.materialName).toBe('气调成品');
    expect(p2.outputUnit).toBe('盒');
    expect(wrapper.text()).toContain('计划成品 500 盒');

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
          processCategory: 'RAW_MATERIAL', defaultUnit: 'kg', defaultOutputUnit: 'kg', defaultEstimatedMinutes: null,
          defaultCostCategory: null, allowSemiFinishedInjection: false,
          allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
          customFieldSchema: null,
        },
        {
          id: 2, productTypeId: 'PT-1', workProcessId: 'WP-L2', processOrder: 2,
          unitOverride: null, estimatedMinutesOverride: null, processName: '焯水',
          processCategory: 'PROCESSING', defaultUnit: 'kg', defaultOutputUnit: 'kg', defaultEstimatedMinutes: null,
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

  it('legacy plan with a missing configured unit blocks instead of silently using kg', async () => {
    getWorkflowSheetConfig.mockResolvedValue({ success: true, data: null });
    getProductWorkProcesses.mockResolvedValue({
      success: true,
      data: [{
        id: 1,
        productTypeId: 'PT-1',
        workProcessId: 'WP-L1',
        processOrder: 1,
        unitOverride: null,
        defaultUnit: null,
        defaultOutputUnit: 'g',
        processName: 'legacy-step',
        processCategory: 'PROCESSING',
        defaultCostCategory: null,
        allowSemiFinishedInjection: false,
        allowMultipleUpstreamSources: false,
        allowFinishedGoodsSource: false,
        customFieldSchema: null,
      }],
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(0);
    expect(wrapper.text()).toContain('单位');
  });

  it('workflow-config reject blocks the sheet, offers retry, and never calls the legacy endpoint', async () => {
    getWorkflowSheetConfig.mockRejectedValue(new Error('network down'));
    getProductWorkProcesses.mockResolvedValue({
      success: true,
      data: [
        {
          id: 1, productTypeId: 'PT-1', workProcessId: 'WP-L1', processOrder: 1,
          unitOverride: null, estimatedMinutesOverride: null, processName: '修油',
          processCategory: 'RAW_MATERIAL', defaultUnit: 'kg', defaultOutputUnit: 'kg', defaultEstimatedMinutes: null,
          defaultCostCategory: null, allowSemiFinishedInjection: false,
          allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
          customFieldSchema: null,
        },
      ],
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getProductWorkProcesses).not.toHaveBeenCalled();
    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(0);
    expect(wrapper.text()).toContain('Workflow 配置加载失败');
    expect(wrapper.text()).toContain('重试');

    getWorkflowSheetConfig.mockResolvedValue({ success: true, data: null });
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重试'));
    if (!retry) throw new Error('找不到重试按钮');
    await retry.trigger('click');
    await flushPromises();
    await flushPromises();

    expect(getWorkflowSheetConfig).toHaveBeenCalledTimes(2);
    expect(getProductWorkProcesses).toHaveBeenCalledWith('F006', 'PT-1');
    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(1);
  });

  it.each([
    ['non-success response', { success: false, data: null }],
    ['malformed response', { success: true, data: { workflowBatchId: 1, processes: 'bad' } }],
    ['empty workflow processes', { success: true, data: { workflowBatchId: 1, processes: [] } }],
    ['missing workflow port unit', {
      success: true,
      data: {
        workflowBatchId: 1,
        workflowInstanceId: 2,
        productTypeId: 'PT-1',
        processes: [{
          workflowNodeId: 'N1', workProcessId: 'WP1', processName: '装件', processCategory: null,
          defaultCostCategory: null, processOrder: 1, plannedUnit: '件',
          allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false, customFieldSchema: null,
          inputs: [{ workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1', materialName: '原料', unit: null, required: true, skuResolved: true, finished: false }],
          output: { workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-1', materialName: '成品', unit: '件', required: true, skuResolved: true, finished: true },
          outputs: [{ workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-1', materialName: '成品', unit: '件', required: true, skuResolved: true, finished: true }],
        }],
      },
    }],
    ['heterogeneous workflow input units', {
      success: true,
      data: {
        workflowBatchId: 1,
        workflowInstanceId: 2,
        productTypeId: 'PT-1',
        processes: [{
          workflowNodeId: 'N1', workProcessId: 'WP1', processName: '混料', processCategory: null,
          defaultCostCategory: null, processOrder: 1, plannedUnit: '件',
          allowMultipleUpstreamSources: true, allowFinishedGoodsSource: false, customFieldSchema: null,
          inputs: [
            { workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1', materialName: '粉料', unit: 'g', required: true, skuResolved: true, finished: false },
            { workflowPortId: 'IN2', materialKind: 'RAW_MATERIAL', skuId: 'RM-2', materialName: '液料', unit: 'ml', required: true, skuResolved: true, finished: false },
          ],
          output: { workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-1', materialName: '成品', unit: '件', required: true, skuResolved: true, finished: true },
          outputs: [{ workflowPortId: 'OUT1', materialKind: 'FINISHED_GOOD', skuId: 'PT-1', materialName: '成品', unit: '件', required: true, skuResolved: true, finished: true }],
        }],
      },
    }],
  ])('%s blocks without invoking legacy', async (_name, response) => {
    getWorkflowSheetConfig.mockResolvedValue(response);
    getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getProductWorkProcesses).not.toHaveBeenCalled();
    expect(wrapper.findAllComponents(ProcessDataTableStub)).toHaveLength(0);
    expect(wrapper.text()).toContain('Workflow 配置加载失败');
    expect(wrapper.text()).toContain('重试');
  });

  it('(3) mapWorkflowProcesses maps a finished output to the qidiao archetype, and forces a semi output away from qidiao even when keyword/position mapping would have picked it (BLOCKING fix, Part B)', async () => {
    getWorkflowSheetConfig.mockResolvedValue({
      success: true,
      data: {
        workflowBatchId: 100,
        workflowInstanceId: 200,
        productTypeId: 'PT-1',
        processes: [
          {
            // idx 0, no keyword match -> position-based default 'xiuyou'; output is semi, so the
            // Part B override never triggers (code was never 'qidiao' to begin with).
            workflowNodeId: 'N1', workProcessId: 'WP1', processName: '领料',
            defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg',
            allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
            customFieldSchema: null,
            inputs: [{ workflowPortId: 'IN1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1', materialName: '原料', unit: 'kg', required: true, skuResolved: true, finished: false }],
            output: {
              workflowPortId: 'OUT1', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1',
              materialName: '领料半成品', unit: 'kg', required: true, skuResolved: true, finished: false,
            },
            outputs: [{ workflowPortId: 'OUT1', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1', materialName: '领料半成品', unit: 'kg', required: true, skuResolved: true, finished: false }],
          },
          {
            // 关键词 "气调" 命中本会映射到 'qidiao' archetype, 但该道 workflow 端口产出实际是半成品
            // (finished:false) —— 必须被强制拉回非 qidiao archetype, 不能因为工序名像气调就渲染
            // 成品录入表单 / 把 finished/unit 硬编码成气调那套 (否则该道每次保存都会撞 B3 校验 409)。
            workflowNodeId: 'N2', workProcessId: 'WP2', processName: '气调',
            defaultCostCategory: null, processOrder: 2, plannedUnit: 'kg',
            allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
            customFieldSchema: null,
            inputs: [{ workflowPortId: 'IN2', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-1', materialName: '领料半成品', unit: 'kg', required: true, skuResolved: true, finished: false }],
            output: {
              workflowPortId: 'OUT2', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-2',
              materialName: '气调半成品(实际未完工)', unit: 'kg', required: true, skuResolved: true, finished: false,
            },
            outputs: [{ workflowPortId: 'OUT2', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-2', materialName: '气调半成品(实际未完工)', unit: 'kg', required: true, skuResolved: true, finished: false }],
          },
          {
            // 无关键词命中 + 非首道 -> 关键词/位置回退本会映射到 'chaoshui', 但该道 workflow 端口
            // 产出实际是成品 (finished:true) —— 必须被强制升级为 'qidiao' archetype, 否则文员永远
            // 存不了这一行 (每次保存撞 WORKFLOW_ROW_OUTPUT_KIND_MISMATCH 409, 这正是本次 BLOCKING
            // correctness fix 要修的 dead-end)。
            workflowNodeId: 'N3', workProcessId: 'WP3', processName: '装箱',
            defaultCostCategory: null, processOrder: 3, plannedUnit: '盒',
            allowMultipleUpstreamSources: false, allowFinishedGoodsSource: false,
            customFieldSchema: null,
            inputs: [{ workflowPortId: 'IN3', materialKind: 'SEMI_FINISHED', skuId: 'PT-SEMI-2', materialName: '气调半成品', unit: 'kg', required: true, skuResolved: true, finished: false }],
            output: {
              workflowPortId: 'OUT3', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1',
              materialName: '装箱成品', unit: '盒', required: true, skuResolved: true, finished: true,
            },
            outputs: [{ workflowPortId: 'OUT3', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN-1', materialName: '装箱成品', unit: '盒', required: true, skuResolved: true, finished: true }],
          },
        ],
      },
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    expect(getWorkflowSheetConfig).toHaveBeenCalledWith('F006', 'PLAN-WF-1');
    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(3);

    expect(tables[0].props().processCode).toBe('xiuyou');

    expect(tables[1].props().processCode).not.toBe('qidiao');
    expect(tables[1].props().processCode).toBe('chaoshui');

    expect(tables[2].props().processCode).toBe('qidiao');
  });

  it('(4) keeps a RAW-only to finished-good workflow on the raw-material intake form', async () => {
    getWorkflowSheetConfig.mockResolvedValue({
      success: true,
      data: {
        workflowBatchId: 101,
        workflowInstanceId: 201,
        productTypeId: 'PT-FIN-RAW',
        processes: [{
          workflowNodeId: 'N-RAW-FIN',
          workProcessId: 'WP-RAW-FIN',
          processName: '定量包装',
          defaultCostCategory: null,
          processOrder: 1,
          plannedUnit: 'kg',
          allowMultipleUpstreamSources: false,
          allowFinishedGoodsSource: false,
          customFieldSchema: null,
          inputs: [
            { workflowPortId: 'IN-A', materialKind: 'RAW_MATERIAL', skuId: 'RM-A', materialName: '原料A', unit: 'kg', required: true, skuResolved: true, finished: false },
            { workflowPortId: 'IN-B', materialKind: 'RAW_MATERIAL', skuId: 'RM-B', materialName: '原料B', unit: 'kg', required: true, skuResolved: true, finished: false },
          ],
          output: { workflowPortId: 'OUT-FIN', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN', materialName: '成品', unit: 'kg', required: true, skuResolved: true, finished: true },
          outputs: [{ workflowPortId: 'OUT-FIN', materialKind: 'FINISHED_GOOD', skuId: 'PT-FIN', materialName: '成品', unit: 'kg', required: true, skuResolved: true, finished: true }],
        }],
      },
    });

    const wrapper = mountSheet();
    await flushPromises();
    await flushPromises();

    const tables = wrapper.findAllComponents(ProcessDataTableStub);
    expect(tables).toHaveLength(1);
    expect(tables[0].props().processCode).toBe('xiuyou');
    expect(tables[0].props().workflowContext.inputs).toHaveLength(2);
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { DOMWrapper } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { ProcessSheetRowRequest, ProcessSheetRowView, WorkflowPortDescriptor, WorkflowProcessDescriptor } from '@/api/processSheet';

const saveDraftRow = vi.fn();
const submitRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: (...args: unknown[]) => saveDraftRow(...args),
    submitRow: (...args: unknown[]) => submitRow(...args),
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

const outputPort: WorkflowPortDescriptor = {
  workflowPortId: 'OUT-1', materialNodeId: 'FG-1', materialKind: 'FINISHED_GOOD',
  skuId: 'FG-1', materialName: '成品', unit: 'kg', required: true, skuResolved: true, finished: true,
};

function rawPort(index: number, group?: Partial<WorkflowPortDescriptor>): WorkflowPortDescriptor {
  return {
    workflowPortId: `IN-${index}`, materialNodeId: `RAW-${index}`, materialKind: 'RAW_MATERIAL',
    skuId: `RM-${index}`, materialName: `原料 ${index}`, unit: 'kg', required: true,
    skuResolved: true, finished: false, ...group,
  };
}

function context(inputs: WorkflowPortDescriptor[], outputs: WorkflowPortDescriptor[] = [outputPort]): WorkflowProcessDescriptor {
  return {
    workflowNodeId: 'PROC-1', workProcessId: 'WP-1', processName: '组合工序', processCategory: 'CUT',
    defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg', allowMultipleUpstreamSources: false,
    allowFinishedGoodsSource: false, customFieldSchema: null, inputs, output: outputs[0], outputs,
  };
}

function mountTable(workflowContext: WorkflowProcessDescriptor, initialRows: ProcessSheetRowView[] = []) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-PORT-GROUPS', processCode: 'xiuyou', processOrder: 1,
      processLabel: '组合工序', productTypeId: 'FG-1', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: true, initialRows, upstreamItems: [], ownInventoryItems: [],
      viewMode: 'card', workflowContext,
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addRow(wrapper: ReturnType<typeof mountTable>) {
  await flushPromises();
  await wrapper.findAll('button').find((button) => button.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

function fillQuantity(line: DOMWrapper<Element>, value: number) {
  line.findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', value);
}

function fillOutputQuantity(line: DOMWrapper<Element>, value: number) {
  line.find('[data-testid="output-quantity"]')
    .findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', value);
}

async function selectLine(line: DOMWrapper<Element>, selected = true) {
  line.findComponent({ name: 'ElCheckbox' }).vm.$emit('change', selected);
  await flushPromises();
}

async function submit(wrapper: ReturnType<typeof mountTable>) {
  await wrapper.findAll('button').find((button) => button.text().includes('正式报工'))!.trigger('click');
  await flushPromises();
}

describe('ProcessDataTable port selection groups', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    vi.restoreAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    saveDraftRow.mockResolvedValue({ success: true, data: { submissionStatus: 'DRAFT', materialized: false } });
    submitRow.mockResolvedValue({ success: true, data: { submissionStatus: 'SUBMITTED', materialized: true, outputs: [] } });
  });

  it('uses backend *MinSelections/*MaxSelections fields and submits one of four substitute raw materials', async () => {
    const group = {
      selectionGroupId: 'raw-substitutes', selectionGroupLabel: '替代原料', selectionGroupMode: 'EXACTLY_ONE' as const,
      selectionGroupMinSelections: 1, selectionGroupMaxSelections: 1,
    };
    const workflow = context([1, 2, 3, 4].map((index) => rawPort(index, group)));
    expect(workflow.inputs[0]).toHaveProperty('selectionGroupMinSelections', 1);
    expect(workflow.inputs[0]).not.toHaveProperty('selectionGroupMin');
    const wrapper = mountTable(workflow);
    await addRow(wrapper);
    const inputs = wrapper.findAll('[data-testid="material-input-total"]');
    await selectLine(inputs[2]);
    fillQuantity(inputs[2], 12);
    fillOutputQuantity(wrapper.find('[data-testid="workflow-output-line"]'), 8);
    await flushPromises();
    await submit(wrapper);
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.materialInputTotals).toEqual([
      expect.objectContaining({ materialTypeId: 'RM-3', workflowPortId: 'IN-3', quantity: 12 }),
    ]);
  });

  it('submits only the selected subset from an AT_LEAST_ONE multi-output group', async () => {
    const group = {
      selectionGroupId: 'outputs', selectionGroupLabel: '可产出规格', selectionGroupMode: 'AT_LEAST_ONE' as const,
      selectionGroupMinSelections: 1, selectionGroupMaxSelections: 3,
    };
    const outputs = [1, 2, 3].map((index): WorkflowPortDescriptor => ({
      ...outputPort, workflowPortId: `OUT-${index}`, materialNodeId: `FG-${index}`,
      skuId: `FG-${index}`, materialName: `成品 ${index}`, ...group,
    }));
    const wrapper = mountTable(context([rawPort(1)], outputs));
    await addRow(wrapper);
    fillQuantity(wrapper.find('[data-testid="material-input-total"]'), 20);
    const outputLines = wrapper.findAll('[data-testid="workflow-output-line"]');
    await selectLine(outputLines[0]);
    await selectLine(outputLines[2]);
    fillOutputQuantity(outputLines[0], 6);
    fillOutputQuantity(outputLines[2], 4);
    await flushPromises();
    await submit(wrapper);
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.outputs?.map((output) => output.workflowPortId)).toEqual(['OUT-1', 'OUT-3']);
  });

  it('keeps legacy ports selected and formally required, without a fake selector', async () => {
    const wrapper = mountTable(context([rawPort(1), rawPort(2)]));
    await addRow(wrapper);
    const inputs = wrapper.findAll('[data-testid="material-input-total"]');
    // legacy 端口没有选择组 = 没有选择余地。永远勾上、永远置灰的复选框只是噪声,
    // 还逼用户先点一下才能填数量 —— 现在直接不渲染。
    expect(inputs.map((line) => line.findAllComponents({ name: 'ElCheckbox' }).length)).toEqual([0, 0]);
    fillQuantity(inputs[0], 5);
    fillOutputQuantity(wrapper.find('[data-testid="workflow-output-line"]'), 4);
    await flushPromises();
    const submitButton = wrapper.findAll('button').find((button) => button.text().includes('正式报工'))!;
    expect(submitButton.attributes('disabled')).toBeDefined();
    // 阻塞原因移到 tooltip: disabled 元素不触发鼠标事件, 原生 title 弹不出来
    const tooltip = wrapper.findAllComponents({ name: 'ElTooltip' })
      .find((component) => String(component.props('content') ?? '').includes('原料 2'));
    expect(tooltip).toBeTruthy();
    expect(tooltip!.props('showAfter')).toBe(0);
  });

  it('applies the same selection contract to upstream input lines', async () => {
    const group = {
      selectionGroupId: 'upstream-substitutes', selectionGroupLabel: '上游替代料', selectionGroupMode: 'EXACTLY_ONE' as const,
      selectionGroupMinSelections: 1, selectionGroupMaxSelections: 1,
    };
    const inputs: WorkflowPortDescriptor[] = [1, 2].map((index) => ({
      workflowPortId: `UP-${index}`, materialNodeId: `SEMI-${index}`, materialKind: 'SEMI_FINISHED',
      skuId: `SEMI-${index}`, materialName: `上游半成品 ${index}`, unit: 'kg', required: true,
      skuResolved: true, finished: false, ...group,
    }));
    const wrapper = mount(ProcessDataTable, {
      props: {
        factoryId: 'F006', planId: 'PLAN-UPSTREAM-GROUP', processCode: 'custom_merge', processOrder: 2,
        processLabel: '合流', productTypeId: 'FG-1', inputUnit: 'kg', outputUnit: 'kg', isFirstProcess: false,
        initialRows: [], ownInventoryItems: [], viewMode: 'card', workflowContext: context(inputs),
        upstreamItems: [
          { batchNumber: 'UP-A', productTypeId: 'SEMI-1', produced: 10, used: 0, remaining: 10, status: 'ACTIVE' as const, unit: 'kg' },
          { batchNumber: 'UP-B', productTypeId: 'SEMI-2', produced: 10, used: 0, remaining: 10, status: 'ACTIVE' as const, unit: 'kg' },
        ],
      },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
    await addRow(wrapper);
    const sources = wrapper.findAll('[data-testid="upstream-source-line"]');
    await selectLine(sources[1]);
    // 勾上「选用」之后这个端口只剩一个可用批次 (UP-B), 界面直接显示批次不再给下拉 ——
    // 客户 2026-07-30「只有一个批次时自动选中, 不要让用户多点一次」。
    // 意图不变: 勾了 UP-2 这一路, 提交的就必须是 UP-B。
    expect(sources[1].find('[data-testid="upstream-batch-fixed"]').text()).toContain('UP-B');
    expect(sources[1].findComponent({ name: 'ElSelect' }).exists()).toBe(false);
    sources[1].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 6);
    fillOutputQuantity(wrapper.find('[data-testid="workflow-output-line"]'), 5);
    await flushPromises();
    await submit(wrapper);
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.upstreamSources).toEqual([
      expect.objectContaining({ workflowPortId: 'UP-2', sourceBatchNumber: 'UP-B', feedQuantityKg: 6 }),
    ]);
  });

  it('saves an incomplete draft and restores the selected port after refresh', async () => {
    const group = {
      selectionGroupId: 'raw-substitutes', selectionGroupLabel: '替代原料', selectionGroupMode: 'EXACTLY_ONE' as const,
      selectionGroupMinSelections: 1, selectionGroupMaxSelections: 1,
    };
    const workflow = context([1, 2, 3, 4].map((index) => rawPort(index, group)));
    const wrapper = mountTable(workflow);
    await addRow(wrapper);
    const inputs = wrapper.findAll('[data-testid="material-input-total"]');
    await selectLine(inputs[1]);
    fillQuantity(inputs[1], 7);
    await wrapper.findAll('button').find((button) => button.text().includes('保存草稿'))!.trigger('click');
    await flushPromises();
    const request = saveDraftRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.materialInputTotals?.map((item) => item.workflowPortId)).toEqual(['IN-2']);

    const refreshed = mountTable(workflow, [{
      clientRowId: request.clientRowId, batchNumber: null, batchId: null, rowStatus: 'DRAFT', submissionStatus: 'DRAFT',
      materialized: false, interimSettledAt: null, payload: request,
    }]);
    await flushPromises();
    const restored = refreshed.findAll('[data-testid="material-input-total"]');
    expect(restored.map((line) => line.findComponent({ name: 'ElCheckbox' }).props('modelValue')))
      .toEqual([false, true, false, false]);
    expect(restored[1].findComponent({ name: 'ElInputNumber' }).props('modelValue')).toBe(7);
  });
});

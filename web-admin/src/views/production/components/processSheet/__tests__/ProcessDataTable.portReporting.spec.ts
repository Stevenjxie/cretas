import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { ProcessSheetRowRequest } from '@/api/processSheet';

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

const workflowContext = {
  workflowNodeId: 'PROC-1', workProcessId: 'WP-1', processName: '联产分切', processCategory: 'CUT',
  defaultCostCategory: null, processOrder: 1, plannedUnit: 'kg', allowMultipleUpstreamSources: false,
  allowFinishedGoodsSource: false, customFieldSchema: null,
  inputs: [
    { workflowPortId: 'IN-CHICKEN', materialNodeId: 'RAW-1', materialKind: 'RAW_MATERIAL', skuId: 'RM-1', materialName: '黄油鸡', unit: 'kg', required: true, skuResolved: true, finished: false },
    { workflowPortId: 'IN-SPICE', materialNodeId: 'RAW-2', materialKind: 'RAW_MATERIAL', skuId: 'RM-2', materialName: '调味液', unit: 'kg', required: true, skuResolved: true, finished: false },
  ],
  output: { workflowPortId: 'OUT-350', materialNodeId: 'FG-1', materialKind: 'FINISHED_GOOD', skuId: 'FG-350', materialName: '干式熟成脆皮鸡 350g', unit: '袋', gramsPerUnit: 350, required: true, skuResolved: true, finished: true },
  outputs: [
    { workflowPortId: 'OUT-350', materialNodeId: 'FG-1', materialKind: 'FINISHED_GOOD', skuId: 'FG-350', materialName: '干式熟成脆皮鸡 350g', unit: '袋', gramsPerUnit: 350, required: true, skuResolved: true, finished: true },
    { workflowPortId: 'OUT-400', materialNodeId: 'FG-2', materialKind: 'FINISHED_GOOD', skuId: 'FG-400', materialName: '干式熟成脆皮鸡 400g', unit: '袋', gramsPerUnit: 400, required: true, skuResolved: true, finished: true },
  ],
};

function mountTable(context = workflowContext) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-JOINT', processCode: 'xiuyou', processOrder: 1,
      processLabel: '联产分切', productTypeId: 'FG-350', inputUnit: 'kg', outputUnit: '袋',
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

describe('ProcessDataTable workflow port reporting rows', () => {
  beforeEach(() => {
    saveDraftRow.mockReset();
    submitRow.mockReset();
    vi.restoreAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    submitRow.mockResolvedValue({
      success: true,
      data: { submissionStatus: 'SUBMITTED', materialized: true, batchNumber: 'FG-JOINT-1', outputs: [] },
    });
  });

  it('renders production date and multiple input/output ports with fixed read-only units', async () => {
    const wrapper = mountTable();
    await addRow(wrapper);

    expect(wrapper.findAll('[data-testid="material-input-total"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="workflow-output-line"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="workflow-execution-line"]')).toHaveLength(2);
    expect(wrapper.find('[data-testid="production-date"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="input-unit-readonly"]').map((item) => item.text())).toEqual(['kg', 'kg']);
    expect(wrapper.findAll('[data-testid="output-unit-readonly"]').map((item) => item.text())).toEqual(['袋', '袋']);
    expect(wrapper.findAll('[data-testid="byproduct-unit-readonly"]').map((item) => item.text())).toEqual(['kg', 'kg']);
    expect(wrapper.find('[data-testid="cost-allocation-ratio"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('每条投入在本报工组中只扣减一次');
    for (const outputLine of wrapper.findAll('[data-testid="workflow-output-line"]')) {
      expect(outputLine.findComponent({ name: 'ElSelect' }).exists()).toBe(false);
      expect(outputLine.text()).toContain('副产回收单价');
    }
    // 作业时间已并进产出行, 字段名由表头统一给出; 每个控件必须自带 aria-label ——
    // 否则表格化之后屏幕阅读器只会念到一串没有名字的输入框。
    for (const executionLine of wrapper.findAll('[data-testid="workflow-execution-line"]')) {
      const labels = executionLine.findAll('[aria-label]').map((node) => node.attributes('aria-label'));
      expect(labels.some((label) => label?.endsWith('开始时间'))).toBe(true);
      expect(labels.some((label) => label?.endsWith('结束时间'))).toBe(true);
      expect(labels.some((label) => label?.endsWith('人数'))).toBe(true);
      expect(executionLine.text()).toContain('h');
    }
    expect(wrapper.text()).toContain('总工时');
  });

  it('submits production date, two input totals and per-output time/byproduct payload without a cartesian matrix', async () => {
    const wrapper = mountTable();
    await addRow(wrapper);

    const inputRows = wrapper.findAll('[data-testid="material-input-total"]');
    inputRows[0].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 20);
    inputRows[1].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 1.2);
    wrapper.find('[data-testid="production-date"]')
      .findComponent({ name: 'ElDatePicker' })
      .vm.$emit('update:model-value', '2026-07-17');

    const outputs = wrapper.findAll('[data-testid="workflow-output-line"]');
    const executionLines = wrapper.findAll('[data-testid="workflow-execution-line"]');
    executionLines[0].find('[data-testid="output-start-time"]').findComponent({ name: 'ElTimePicker' }).vm.$emit('update:model-value', '08:00');
    executionLines[0].find('[data-testid="output-end-time"]').findComponent({ name: 'ElTimePicker' }).vm.$emit('update:model-value', '09:30');
    executionLines[0].find('[data-testid="output-worker-count"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 2);
    outputs[0].find('[data-testid="output-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 30);
    outputs[0].find('[data-testid="byproduct-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 0.5);
    outputs[0].find('[data-testid="byproduct-unit-price"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 3.2);
    executionLines[1].find('[data-testid="output-start-time"]').findComponent({ name: 'ElTimePicker' }).vm.$emit('update:model-value', '09:30');
    executionLines[1].find('[data-testid="output-end-time"]').findComponent({ name: 'ElTimePicker' }).vm.$emit('update:model-value', '10:30');
    outputs[1].find('[data-testid="output-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 20);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('投入明细按本次报工组只扣减一次'),
      '确认正式报工',
      expect.any(Object),
    );
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.processDate).toBe('2026-07-17');
    expect(request.materialInputTotals).toHaveLength(2);
    expect(request.outputs).toHaveLength(2);
    expect(request.outputs[0]).toMatchObject({
      productTypeId: 'FG-350', quantity: 30, unit: '袋',
      laborSegments: [{ startTime: '08:00', endTime: '09:30', workerCount: 2 }],
      byproducts: [{ name: '副产', quantity: 0.5, unit: 'kg', unitPrice: 3.2 }],
    });
    expect(request.outputs[1]).toMatchObject({
      productTypeId: 'FG-400', quantity: 20, unit: '袋',
      laborSegments: [{ startTime: '09:30', endTime: '10:30', workerCount: 1 }],
    });
    expect(request.outputs).toHaveLength(2);
    expect(request.materialInputTotals).toHaveLength(2);
  });

  it('normalizes g/kg before displaying output yield', async () => {
    const massContext = {
      ...workflowContext,
      inputs: [
        { workflowPortId: 'IN-KG', materialNodeId: 'RAW-KG', materialKind: 'RAW_MATERIAL', skuId: 'RM-KG', materialName: '原料', unit: 'kg', required: true, skuResolved: true, finished: false },
      ],
      output: { workflowPortId: 'OUT-G', materialNodeId: 'FG-G', materialKind: 'FINISHED_GOOD', skuId: 'FG-G', materialName: '500g 成品', unit: 'g', required: true, skuResolved: true, finished: true },
      outputs: [
        { workflowPortId: 'OUT-G', materialNodeId: 'FG-G', materialKind: 'FINISHED_GOOD', skuId: 'FG-G', materialName: '500g 成品', unit: 'g', required: true, skuResolved: true, finished: true },
      ],
    };
    const wrapper = mountTable(massContext);
    await addRow(wrapper);
    wrapper.find('[data-testid="material-input-total"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 1);
    wrapper.find('[data-testid="output-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 500);
    await flushPromises();

    expect(wrapper.find('[data-testid="workflow-output-line"]').text()).toContain('50.00%');
    expect(wrapper.find('[data-testid="workflow-output-line"]').text()).not.toContain('50000.00%');
  });

  it('requires and submits cost ratios only when output dimensions cannot be unified', async () => {
    const mixedContext = {
      ...workflowContext,
      inputs: [workflowContext.inputs[0]],
      output: { workflowPortId: 'OUT-KG', materialNodeId: 'SEMI-KG', materialKind: 'SEMI_FINISHED', skuId: 'SEMI-KG', materialName: '合格半成品', unit: 'kg', required: true, skuResolved: true, finished: false },
      outputs: [
        { workflowPortId: 'OUT-KG', materialNodeId: 'SEMI-KG', materialKind: 'SEMI_FINISHED', skuId: 'SEMI-KG', materialName: '合格半成品', unit: 'kg', required: true, skuResolved: true, finished: false },
        { workflowPortId: 'OUT-BOX', materialNodeId: 'FG-BOX', materialKind: 'FINISHED_GOOD', skuId: 'FG-BOX', materialName: '装箱成品', unit: '箱', gramsPerUnit: null, required: true, skuResolved: true, finished: true },
      ],
    };
    const wrapper = mountTable(mixedContext);
    await addRow(wrapper);
    wrapper.find('[data-testid="material-input-total"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
    const outputs = wrapper.findAll('[data-testid="workflow-output-line"]');
    outputs[0].find('[data-testid="output-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 8);
    outputs[1].find('[data-testid="output-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 2);
    expect(wrapper.findAll('[data-testid="cost-allocation-ratio"]')).toHaveLength(2);
    outputs[0].find('[data-testid="cost-allocation-ratio"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 70);
    outputs[1].find('[data-testid="cost-allocation-ratio"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 30);
    await flushPromises();

    await wrapper.findAll('button').find((item) => item.text().includes('正式报工'))!.trigger('click');
    await flushPromises();
    const request = submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
    expect(request.outputs?.map((output) => output.costAllocationRatio)).toEqual([70, 30]);
  });
});

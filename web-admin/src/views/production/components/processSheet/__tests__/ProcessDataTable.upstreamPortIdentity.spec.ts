import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { ProcessSheetRowRequest, WorkflowProcessDescriptor } from '@/api/processSheet';

const submitRow = vi.fn();

vi.mock('@/api/processSheet', async () => {
  const actual = await vi.importActual<typeof import('@/api/processSheet')>('@/api/processSheet');
  return {
    ...actual,
    saveDraftRow: vi.fn(),
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

const inputPorts: WorkflowProcessDescriptor['inputs'] = [
  { workflowPortId: 'IN-A', materialNodeId: 'NODE-A', materialKind: 'SEMI_FINISHED', skuId: 'SKU-A', materialName: '前处理鸡肉', unit: 'kg', required: true, skuResolved: true, finished: false },
  { workflowPortId: 'IN-B', materialNodeId: 'NODE-B', materialKind: 'SEMI_FINISHED', skuId: 'SKU-B', materialName: '调味半成品', unit: 'g', required: true, skuResolved: true, finished: false },
];

function workflow(outputsCount: 1 | 2): WorkflowProcessDescriptor {
  const outputs: WorkflowProcessDescriptor['outputs'] = [
    { workflowPortId: 'OUT-A', materialNodeId: 'OUT-NODE-A', materialKind: 'SEMI_FINISHED', skuId: 'OUT-A', materialName: '合流半成品', unit: 'kg', required: true, skuResolved: true, finished: false },
  ];
  if (outputsCount === 2) {
    outputs.push({ workflowPortId: 'OUT-B', materialNodeId: 'OUT-NODE-B', materialKind: 'SEMI_FINISHED', skuId: 'OUT-B', materialName: '副线半成品', unit: 'kg', required: true, skuResolved: true, finished: false });
  }
  return {
    workflowNodeId: 'PROC-MERGE', workProcessId: 'WP-MERGE', processName: '合流工序', processCategory: 'MERGE',
    defaultCostCategory: null, processOrder: 2, plannedUnit: 'kg', allowMultipleUpstreamSources: false,
    allowFinishedGoodsSource: false, customFieldSchema: null,
    inputs: inputPorts, output: outputs[0], outputs,
  };
}

const upstreamItems = [
  { batchNumber: 'A-001', productTypeId: 'SKU-A', productTypeName: '前处理鸡肉', produced: 20, used: 0, remaining: 20, status: 'ACTIVE' as const, unit: 'kg' },
  { batchNumber: 'B-001', productTypeId: 'SKU-B', productTypeName: '调味半成品', produced: 5000, used: 0, remaining: 5000, status: 'ACTIVE' as const, unit: 'g' },
  { batchNumber: 'LEGACY-001', productTypeName: '旧批次', produced: 5, used: 0, remaining: 5, status: 'ACTIVE' as const, unit: 'kg' },
];

function mountTable(outputsCount: 1 | 2) {
  return mount(ProcessDataTable, {
    props: {
      factoryId: 'F006', planId: 'PLAN-MERGE', processCode: 'custom_merge', processOrder: 2,
      processLabel: '合流工序', productTypeId: 'OUT-A', inputUnit: 'kg', outputUnit: 'kg',
      isFirstProcess: false, initialRows: [], upstreamItems, ownInventoryItems: [],
      viewMode: 'card', workflowContext: workflow(outputsCount),
    },
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
}

async function addAndExpand(wrapper: ReturnType<typeof mountTable>) {
  await flushPromises();
  await wrapper.findAll('button').find((button) => button.text().includes('新增行'))!.trigger('click');
  await flushPromises();
}

async function fillAndSubmit(wrapper: ReturnType<typeof mountTable>) {
  const sources = wrapper.findAll('[data-testid="upstream-source-line"]');
  sources[0].findComponent({ name: 'ElSelect' }).vm.$emit('change', 'wip::A-001');
  sources[0].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 10);
  sources[1].findComponent({ name: 'ElSelect' }).vm.$emit('change', 'wip::B-001');
  sources[1].findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', 1000);
  const outputs = wrapper.findAll('[data-testid="workflow-output-line"]');
  outputs.forEach((output, index) => {
    output.find('[data-testid="output-quantity"]').findComponent({ name: 'ElInputNumber' }).vm.$emit('update:model-value', index + 1);
  });
  await flushPromises();
  await wrapper.findAll('button').find((button) => button.text().includes('正式报工'))!.trigger('click');
  await flushPromises();
  return submitRow.mock.calls[0][2] as ProcessSheetRowRequest;
}

describe('ProcessDataTable workflow upstream port identity', () => {
  beforeEach(() => {
    submitRow.mockReset();
    vi.restoreAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    submitRow.mockResolvedValue({ success: true, data: { submissionStatus: 'SUBMITTED', materialized: true, outputs: [] } });
  });

  it('renders two fixed input ports, filters batches by SKU and submits 2-input/1-output identities', async () => {
    const wrapper = mountTable(1);
    await addAndExpand(wrapper);
    const sources = wrapper.findAll('[data-testid="upstream-source-line"]');
    expect(sources).toHaveLength(2);
    expect(sources.map((line) => line.find('[data-testid="input-port-name"]').text())).toEqual(['前处理鸡肉', '调味半成品']);
    expect(sources.map((line) => line.find('[data-testid="input-unit-readonly"]').text())).toEqual(['kg', 'g']);
    expect(sources[0].text()).toContain('同物料再加批次');
    expect(sources[1].text()).toContain('同物料再加批次');

    const request = await fillAndSubmit(wrapper);
    expect(request.outputs).toBeUndefined();
    expect(request.upstreamSources).toEqual([
      expect.objectContaining({ sourceBatchNumber: 'A-001', workflowPortId: 'IN-A', materialNodeId: 'NODE-A', skuId: 'SKU-A' }),
      expect.objectContaining({ sourceBatchNumber: 'B-001', workflowPortId: 'IN-B', materialNodeId: 'NODE-B', skuId: 'SKU-B' }),
    ]);
  });

  it('keeps every source identity in a 2-input/2-output request', async () => {
    const wrapper = mountTable(2);
    await addAndExpand(wrapper);
    const request = await fillAndSubmit(wrapper);
    expect(request.outputs).toHaveLength(2);
    expect(request.upstreamSources?.map((source) => ({
      workflowPortId: source.workflowPortId,
      materialNodeId: source.materialNodeId,
      skuId: source.skuId,
    }))).toEqual([
      { workflowPortId: 'IN-A', materialNodeId: 'NODE-A', skuId: 'SKU-A' },
      { workflowPortId: 'IN-B', materialNodeId: 'NODE-B', skuId: 'SKU-B' },
    ]);
  });
});

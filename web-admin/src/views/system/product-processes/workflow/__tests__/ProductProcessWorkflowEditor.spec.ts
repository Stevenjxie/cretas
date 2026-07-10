import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductWorkProcesses: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: apiMocks.get,
  post: apiMocks.post,
}));

vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: apiMocks.getActiveWorkProcesses,
  getProductWorkProcesses: apiMocks.getProductWorkProcesses,
}));

vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: apiMocks.getProductProcessWorkflow,
  publishProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
}));

vi.mock('@vue-flow/core', async () => {
  const { defineComponent } = await import('vue');
  return {
    MarkerType: { ArrowClosed: 'arrow-closed' },
    VueFlow: defineComponent({ name: 'VueFlow', template: '<div />' }),
    useVueFlow: () => ({
      fitView: vi.fn(),
      getViewport: () => ({ x: 0, y: 0, zoom: 1 }),
      setViewport: vi.fn(),
    }),
  };
});

vi.mock('@vue-flow/background', async () => {
  const { defineComponent } = await import('vue');
  return { Background: defineComponent({ name: 'Background', template: '<div />' }) };
});

vi.mock('@vue-flow/controls', async () => {
  const { defineComponent } = await import('vue');
  return { Controls: defineComponent({ name: 'Controls', template: '<div />' }) };
});

interface EditorVm {
  flowNodes: EditorNode[];
  flowEdges: Array<{ id: string; source: string; target: string }>;
  history: unknown[];
  outputSkuOptions: SkuOption[];
  selectedWorkProcessId: string;
  openAddProcess: (materialNodeId: string) => void;
  confirmAddProcess: () => void;
  addOutputToProcess: (processId: string) => void;
  selectOutputSku: (processId: string, portId: string, skuId: string) => void;
  confirmCreateSku: () => Promise<void>;
}

interface TestPort {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  materialNodeId?: string;
  materialKind?: string;
  skuId?: string;
  ordinal: number;
}

interface EditorNode {
  id: string;
  position: { x: number; y: number };
  data: Record<string, unknown> & {
    kind?: string;
    ports?: TestPort[];
  };
}

interface SkuOption {
  id: string;
  name: string;
  unit: string;
  productCategory: string;
}

const SKU_OPTIONS: SkuOption[] = [
  { id: 'SKU-SEMI', name: 'Semi output', unit: 'kg', productCategory: 'SEMI_FINISHED' },
  { id: 'SKU-FINISHED', name: 'Finished output', unit: 'kg', productCategory: 'FINISHED_PRODUCT' },
  { id: 'SKU-CONTRACT', name: 'Contract output', unit: 'kg', productCategory: 'CONTRACT_MANUFACTURING' },
  { id: 'SKU-CUSTOMER', name: 'Customer output', unit: 'kg', productCategory: 'CUSTOMER_MATERIAL' },
  { id: 'SKU-RAW', name: 'Raw input', unit: 'kg', productCategory: 'RAW_MATERIAL' },
  { id: 'SKU-UNKNOWN', name: 'Unknown category', unit: 'kg', productCategory: 'MYSTERY' },
];

describe('ProductProcessWorkflowEditor process branch integration', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    let timestamp = 1_000;
    vi.spyOn(Date, 'now').mockImplementation(() => timestamp++);
    apiMocks.get.mockImplementation((url: string) => Promise.resolve(
      url.includes('/product-types')
        ? { success: true, data: { content: SKU_OPTIONS } }
        : { success: true, data: [] },
    ));
    apiMocks.post.mockResolvedValue({
      success: true,
      data: {
        id: 'SKU-CREATED-SEMI',
        name: 'Created semi output',
        unit: 'kg',
        productCategory: 'SEMI_FINISHED',
      },
    });
    apiMocks.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getActiveWorkProcesses.mockResolvedValue({
      success: true,
      data: [{
        id: 'WP-PACK',
        processName: '装盒',
        processCategory: 'PACKING',
        unit: 'kg',
        estimatedMinutes: 10,
        sortOrder: 1,
        isActive: true,
        standardYieldMin: null,
        standardYieldMax: null,
        needsInput: true,
        outputUnit: '盒',
        defaultOutputMaterialKind: 'FINISHED_GOOD',
        semiFinishedOutputCode: null,
        standardHourlyRate: null,
        customFieldSchema: null,
        createdAt: '2026-07-10T00:00:00Z',
        updatedAt: '2026-07-10T00:00:00Z',
      }],
    });
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: {
        id: 1,
        productTypeId: 'PT-PIG-400',
        schemaVersion: 1,
        status: 'DRAFT',
        version: 1,
        nodes: [{
          id: 'raw',
          kind: 'RAW_MATERIAL',
          position: { x: 16, y: 32 },
          data: { name: '猪蹄原料', skuId: 'RM-PIG', baseUnit: 'kg' },
        }],
        edges: [],
        viewport: { x: 0, y: 0, zoom: 1 },
      },
    });
  });

  it('adds the process, derived finished output, and both edges in one undo snapshot', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();

    expect(vm.flowNodes).toHaveLength(3);
    expect(vm.flowEdges).toHaveLength(2);
    expect(vm.history).toHaveLength(1);
    expect(vm.flowNodes.find((node) => node.data.kind === 'FINISHED_GOOD')?.data).toMatchObject({
      name: '五香去骨猪蹄 400g',
      skuId: 'PT-PIG-400',
      skuCode: 'PT-PIG-400',
      bound: true,
    });
    expect(vm.flowEdges).toEqual(expect.arrayContaining([
      expect.objectContaining({ source: 'raw' }),
      expect.objectContaining({ target: expect.stringContaining('material:finished:') }),
    ]));
  });

  it.each([
    ['SKU-SEMI', 'SEMI_FINISHED'],
    ['SKU-FINISHED', 'FINISHED_GOOD'],
    ['SKU-CONTRACT', 'FINISHED_GOOD'],
    ['SKU-CUSTOMER', 'FINISHED_GOOD'],
  ] as const)('binds %s as %s on both the port and material Cell', async (skuId, expectedKind) => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);

    vm.selectOutputSku(process.id, port.id, skuId);

    expect(port).toMatchObject({ skuId, materialKind: expectedKind });
    expect(material.data).toMatchObject({ skuId, kind: expectedKind, bound: true });
  });

  it('filters input-only and unknown SKU categories out of the output dropdown', async () => {
    const vm = await mountEditor();

    expect(vm.outputSkuOptions.map((option) => option.id)).toEqual([
      'SKU-SEMI',
      'SKU-FINISHED',
      'SKU-CONTRACT',
      'SKU-CUSTOMER',
    ]);
  });

  it('rejects an invalid output SKU without changing the port or material Cell', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined);
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);
    const originalPort = { ...port };
    const originalMaterialData = { ...material.data };
    const originalHistoryLength = vm.history.length;

    vm.selectOutputSku(process.id, port.id, 'SKU-RAW');

    expect(errorSpy).toHaveBeenCalledWith('所选 SKU 分类不能作为工序产出');
    expect(port).toEqual(originalPort);
    expect(material.data).toEqual(originalMaterialData);
    expect(vm.history).toHaveLength(originalHistoryLength);
  });

  it('keeps onsite-created output SKUs explicitly semi-finished', async () => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);

    vm.selectOutputSku(process.id, port.id, '__CREATE__');
    await vm.confirmCreateSku();

    expect(apiMocks.post).toHaveBeenCalledWith('/F006/product-types', expect.objectContaining({
      productCategory: 'SEMI_FINISHED',
    }));
    expect(port).toMatchObject({
      skuId: 'SKU-CREATED-SEMI',
      materialKind: 'SEMI_FINISHED',
    });
    expect(material.data).toMatchObject({
      skuId: 'SKU-CREATED-SEMI',
      kind: 'SEMI_FINISHED',
      bound: true,
    });
  });

  it('keeps rapid output node, port, and edge IDs unique within one millisecond', async () => {
    vi.mocked(Date.now).mockReturnValue(2_000);
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');
    const historyBeforeOutputs = vm.history.length;

    vm.addOutputToProcess(process.id);
    vm.addOutputToProcess(process.id);

    const outputPorts = process.data.ports?.filter((port) => port.direction === 'OUTPUT') ?? [];
    const addedOutputPorts = outputPorts.filter((port) => port.ordinal > 0);
    const addedMaterials = addedOutputPorts.map((port) => {
      const material = vm.flowNodes.find((node) => node.id === port.materialNodeId);
      if (!material) throw new Error(`Expected material for ${port.id}`);
      return material;
    });
    expect(vm.history).toHaveLength(historyBeforeOutputs + 2);
    expect(new Set(vm.flowNodes.map((node) => node.id)).size).toBe(vm.flowNodes.length);
    expect(new Set(outputPorts.map((port) => port.id)).size).toBe(outputPorts.length);
    expect(new Set(vm.flowEdges.map((edge) => edge.id)).size).toBe(vm.flowEdges.length);
    expect(addedOutputPorts.map((port) => port.ordinal)).toEqual([1, 2]);
    expect(addedMaterials.map((material) => material.position.y)).toEqual([
      process.position.y + 160,
      process.position.y + 320,
    ]);
    expect(addedMaterials.every((material) => material.position.y % 16 === 0)).toBe(true);
  });
});

async function mountEditor(): Promise<EditorVm> {
  const wrapper = shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-PIG-400',
      productName: '五香去骨猪蹄 400g',
      canWrite: true,
    },
  });
  await flushPromises();
  return wrapper.vm as unknown as EditorVm;
}

function addSecondOutput(vm: EditorVm): { process: EditorNode; port: TestPort; material: EditorNode } {
  vm.openAddProcess('raw');
  vm.selectedWorkProcessId = 'WP-PACK';
  vm.confirmAddProcess();
  const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
  if (!process) throw new Error('Expected process node');

  vm.addOutputToProcess(process.id);
  const port = process.data.ports?.find((candidate) => candidate.ordinal === 1);
  if (!port?.materialNodeId) throw new Error('Expected second output port');
  const material = vm.flowNodes.find((node) => node.id === port.materialNodeId);
  if (!material) throw new Error('Expected second output material Cell');
  return { process, port, material };
}

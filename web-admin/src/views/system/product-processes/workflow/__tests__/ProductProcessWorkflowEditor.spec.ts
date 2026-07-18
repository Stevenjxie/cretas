import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import type { ProductProcessWorkflowDefinition } from '../types';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  createWorkProcess: vi.fn(),
  updateWorkProcess: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
  snapshotProductProcessWorkflow: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: apiMocks.get,
  post: apiMocks.post,
  put: apiMocks.put,
}));

vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: apiMocks.getActiveWorkProcesses,
  getProductWorkProcesses: apiMocks.getProductWorkProcesses,
  createWorkProcess: apiMocks.createWorkProcess,
  updateWorkProcess: apiMocks.updateWorkProcess,
}));

vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: apiMocks.getProductProcessWorkflow,
  getProductProcessWorkflowActivation: apiMocks.getProductProcessWorkflowActivation,
  publishProductProcessWorkflow: apiMocks.publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft: apiMocks.saveProductProcessWorkflowDraft,
  snapshotProductProcessWorkflow: apiMocks.snapshotProductProcessWorkflow,
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
  flowEdges: Array<{ id: string; source: string; target: string; selected?: boolean }>;
  history: unknown[];
  outputSkuOptions: SkuOption[];
  selectedWorkProcessId: string;
  processCreateMode: 'existing' | 'create';
  newProcessForm: { name: string; outputKind: 'SEMI_FINISHED' | 'FINISHED_GOOD' };
  openAddProcess: (materialNodeId: string) => void;
  confirmAddProcess: () => void;
  confirmCreateAndAddProcess: () => Promise<void>;
  addOutputToProcess: (processId: string) => void;
  addInputToProcess: (processId: string) => void;
  addStandaloneRaw: () => void;
  selectOutputSku: (processId: string, portId: string, skuId: string) => Promise<void>;
  selectMaterialSku: (materialNodeId: string, skuId: string) => Promise<void>;
  selectRawSku: (materialNodeId: string, skuId: string) => void;
  openQuickEditProcess: (processNodeId: string) => void;
  saveQuickEditProcess: () => Promise<void>;
  processEditForm: {
    processName: string; processCategory: string; unit: string; outputUnit: string;
    defaultOutputMaterialKind: 'SEMI_FINISHED' | 'FINISHED_GOOD'; needsInput: boolean;
  };
  confirmCreateSku: () => Promise<void>;
  skuForm: { name: string; unit: string };
  bomMissingProducts: Array<{ id: string; name: string }>;
  publishDisabledReason: string;
  publishWorkflow: () => Promise<void>;
  undo: () => void;
  removeSelectedElements: () => void;
  onNodeClick: (payload: { node: EditorNode; event?: MouseEvent }) => void;
  onEdgeClick: (payload: { edge: { id: string; source: string; target: string } }) => void;
  selectedNodeContext: {
    selectedNodeIds: string[];
    selectedEdgeIds: string[];
  };
  snapshotWorkflow: () => Promise<void>;
  definition: ProductProcessWorkflowDefinition;
  dirty: boolean;
}

interface TestPort {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  materialNodeId?: string;
  materialKind?: string;
  skuId?: string;
  unit?: string;
  standardQuantity?: number;
  quantityMode?: 'AUTO_CONVERT' | 'FIXED_RATIO';
  ordinal: number;
}

interface EditorNode {
  id: string;
  selected?: boolean;
  position: { x: number; y: number };
  data: Record<string, unknown> & {
    kind?: string;
    ports?: TestPort[];
    portGroups?: Array<{
      id: string;
      direction: 'INPUT' | 'OUTPUT';
      label: string;
      mode: 'ALL_REQUIRED' | 'EXACTLY_ONE' | 'AT_LEAST_ONE' | 'OPTIONAL';
      minSelections: number;
      maxSelections: number;
      portIds: string[];
    }>;
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
  { id: 'SKU-COUNT', name: 'Count output', unit: '只', productCategory: 'SEMI_FINISHED' },
  { id: 'SKU-COUNT-PIECE', name: 'Piece output', unit: '件', productCategory: 'SEMI_FINISHED' },
  { id: 'SKU-FINISHED', name: 'Finished output', unit: 'kg', productCategory: 'FINISHED_PRODUCT' },
  { id: 'SKU-FIN-800', name: 'Finished 800g', unit: '盒', productCategory: 'FINISHED_PRODUCT' },
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
    apiMocks.get.mockImplementation((url: string) => {
      if (url.endsWith('/product-types/options')) {
        return Promise.resolve({ success: true, data: { content: [...SKU_OPTIONS] } });
      }
      if (url.includes('/product-types/SKU-FIN-800')) {
        return Promise.resolve({ success: true, data: { ...SKU_OPTIONS.find((item) => item.id === 'SKU-FIN-800'), gramsPerUnit: 800 } });
      }
      if (url.includes('/product-types/')) {
        const id = url.split('/').at(-1);
        return Promise.resolve({ success: true, data: SKU_OPTIONS.find((item) => item.id === id) });
      }
      if (url.endsWith('/raw-material-types/active')) {
        return Promise.resolve({ success: true, data: [
          { id: 'RM-PIG', name: '猪蹄原料', unit: 'kg', category: '原料' },
          { id: 'RM-CHICKEN', name: '鸡肉原料', unit: 'kg', category: '原料' },
        ] });
      }
      return Promise.resolve({ success: true, data: [] });
    });
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
    apiMocks.createWorkProcess.mockResolvedValue({
      success: true,
      data: {
        id: 'WP-CREATED', processName: '现场切分', processCategory: '加工', unit: 'g', outputUnit: 'g',
        defaultOutputMaterialKind: 'SEMI_FINISHED', isActive: true,
      },
    });
    apiMocks.updateWorkProcess.mockImplementation((_factoryId: string, id: string, payload: Record<string, unknown>) => Promise.resolve({
      success: true,
      data: {
        id, processName: '装盒', processCategory: 'PACKING', unit: 'kg', outputUnit: '盒',
        needsInput: true, defaultOutputMaterialKind: 'FINISHED_GOOD', ...payload,
      },
    }));
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: {
        id: 1,
        productTypeId: 'PT-PIG-400',
        schemaVersion: 1,
        status: 'DRAFT',
        version: 1,
        lockVersion: 0,
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
    apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({ success: true, data: null });
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

  it('blocks publish before any write when the finished product has no BOM', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor();
    expect(vm.bomMissingProducts).toEqual([{ id: 'PT-PIG-400', name: '五香去骨猪蹄 400g' }]);
    expect(vm.publishDisabledReason).toContain('五香去骨猪蹄 400g 尚未配置原辅料 BOM');

    await vm.publishWorkflow();

    expect(warning).toHaveBeenCalledWith('请先为所有成品产出配置原辅料 BOM，再发布并启用 Workflow');
    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();
  });

  it('treats undo as a new edit and reschedules autosave', async () => {
    const timerSpy = vi.spyOn(globalThis, 'setTimeout');
    const vm = await mountEditor();
    const originalCount = vm.flowNodes.length;
    vm.addStandaloneRaw();
    const timersAfterMutation = timerSpy.mock.calls.length;

    vm.undo();
    expect(vm.flowNodes).toHaveLength(originalCount);
    expect(vm.dirty).toBe(true);
    expect(timerSpy.mock.calls.length).toBeGreaterThan(timersAfterMutation);
    expect(timerSpy.mock.calls.at(-1)?.[1]).toBe(2500);

  });

  it('deletes a batch-selected Cell and every touching link in one undo snapshot', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const beforeDelete = vm.flowNodes.length;
    const historyBeforeDelete = vm.history.length;
    const raw = vm.flowNodes.find((node) => node.id === 'raw');
    if (!raw) throw new Error('Expected raw node');
    raw.selected = true;

    vm.removeSelectedElements();
    await flushPromises();

    expect(vm.flowNodes).toHaveLength(beforeDelete - 1);
    expect(vm.flowEdges).toHaveLength(1);
    expect(vm.flowEdges.some((edge) => edge.source === 'raw' || edge.target === 'raw')).toBe(false);
    expect(vm.history).toHaveLength(historyBeforeDelete + 1);
    vm.undo();
    expect(vm.flowNodes).toHaveLength(beforeDelete);
    expect(vm.flowEdges).toHaveLength(2);
  });

  it('selecting a Cell also selects every directly connected line', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const raw = vm.flowNodes.find((node) => node.id === 'raw');
    if (!raw) throw new Error('Expected raw node');

    vm.onNodeClick({ node: raw });

    expect(vm.flowEdges.filter((edge) => edge.selected)).toHaveLength(1);
    expect(vm.flowEdges.find((edge) => edge.selected)).toMatchObject({ source: 'raw' });
    expect(vm.selectedNodeContext.selectedNodeIds).toEqual(['raw']);
    expect(vm.selectedNodeContext.selectedEdgeIds).toHaveLength(1);
  });

  it('allows a line to be selected independently after a Cell selection', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const raw = vm.flowNodes.find((node) => node.id === 'raw');
    const outputEdge = vm.flowEdges.find((edge) => edge.source !== 'raw');
    if (!raw || !outputEdge) throw new Error('Expected raw Cell and output line');

    vm.onNodeClick({ node: raw });
    vm.onEdgeClick({ edge: outputEdge });

    expect(vm.flowNodes.filter((node) => node.selected)).toHaveLength(0);
    expect(vm.flowEdges.filter((edge) => edge.selected)).toEqual([
      expect.objectContaining({ id: outputEdge.id }),
    ]);
  });

  it('stores an independent version and continues with the incremented draft', async () => {
    const vm = await mountEditor();
    apiMocks.snapshotProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: { ...JSON.parse(JSON.stringify(vm.definition)), version: 2, lockVersion: 1 },
    });

    await vm.snapshotWorkflow();

    expect(apiMocks.snapshotProductProcessWorkflow).toHaveBeenCalledWith('F006', 'PT-PIG-400', 0);
    expect(vm.definition).toMatchObject({ status: 'DRAFT', version: 2, lockVersion: 1 });
    expect(vm.dirty).toBe(false);
  });

  it('uses the upstream material unit only as the onsite-created process legacy payload', async () => {
    const vm = await mountEditor();
    const raw = vm.flowNodes.find((node) => node.id === 'raw');
    if (!raw) throw new Error('Expected raw source');
    raw.data.baseUnit = 'g';
    vm.openAddProcess('raw');
    vm.processCreateMode = 'create';
    vm.newProcessForm.name = '现场切分';

    await vm.confirmCreateAndAddProcess();

    expect(apiMocks.createWorkProcess).toHaveBeenCalledWith('F006', expect.objectContaining({
      processName: '现场切分', unit: 'g', outputUnit: 'g',
    }));
  });

  it('binds a semi-finished SKU on both the port and material Cell', async () => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);

    await vm.selectOutputSku(process.id, port.id, 'SKU-SEMI');

    expect(port).toMatchObject({ skuId: 'SKU-SEMI', materialKind: 'SEMI_FINISHED' });
    expect(material.data).toMatchObject({ skuId: 'SKU-SEMI', kind: 'SEMI_FINISHED', bound: true });
  });

  it.each(['SKU-FINISHED', 'SKU-CONTRACT', 'SKU-CUSTOMER'])(
    'allows a second finished terminal %s because topology determines the Workflow type',
    async (skuId) => {
      const vm = await mountEditor();
      const { process, port, material } = addSecondOutput(vm);

      await vm.selectOutputSku(process.id, port.id, skuId);

      expect(port).toMatchObject({ skuId, materialKind: 'FINISHED_GOOD' });
      expect(material.data).toMatchObject({ skuId, kind: 'FINISHED_GOOD', bound: true });
    },
  );

  it('does not let a legacy raw anchor lock additional roots or process inputs', async () => {
    const vm = await mountEditor(true);
    vm.addStandaloneRaw();
    expect(vm.flowNodes.filter((node) => node.data.kind === 'RAW_MATERIAL')).toHaveLength(2);

    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');
    vm.addInputToProcess(process.id);

    expect(process.data.ports?.filter((port) => port.direction === 'INPUT')).toHaveLength(2);
  });

  it('binds a SKU selected on the SEMI output material Cell to the owning process OUTPUT port', async () => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);

    await vm.selectMaterialSku(material.id, 'SKU-SEMI');

    expect(port).toMatchObject({ skuId: 'SKU-SEMI', materialKind: 'SEMI_FINISHED' });
    expect(material.data).toMatchObject({ skuId: 'SKU-SEMI', kind: 'SEMI_FINISHED', bound: true });
    expect(process.id).toBeTruthy();
  });

  it('opens the create-SKU dialog when __CREATE__ is selected on the material Cell', async () => {
    const vm = await mountEditor();
    const { port, material } = addSecondOutput(vm);

    await vm.selectMaterialSku(material.id, '__CREATE__');
    await vm.confirmCreateSku();

    expect(apiMocks.post).toHaveBeenCalledWith('/F006/product-types', expect.objectContaining({
      productCategory: 'SEMI_FINISHED',
    }));
    expect(port).toMatchObject({ skuId: 'SKU-CREATED-SEMI', materialKind: 'SEMI_FINISHED' });
    expect(material.data).toMatchObject({ skuId: 'SKU-CREATED-SEMI', kind: 'SEMI_FINISHED', bound: true });
  });

  it('warns and does nothing when a material Cell has no owning process OUTPUT port', async () => {
    const warnSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor();
    const rawNode = vm.flowNodes.find((node) => node.data.kind === 'RAW_MATERIAL');
    if (!rawNode) throw new Error('Expected raw material node');
    const historyBefore = vm.history.length;

    await vm.selectMaterialSku(rawNode.id, 'SKU-SEMI');

    expect(warnSpy).toHaveBeenCalledWith('未找到该产出 Cell 对应的工序，无法绑定 SKU');
    expect(vm.history).toHaveLength(historyBefore);
  });

  it('filters input-only and unknown SKU categories out of the output dropdown', async () => {
    const vm = await mountEditor();

    expect(vm.outputSkuOptions.map((option) => option.id)).toEqual([
      'SKU-SEMI',
      'SKU-COUNT',
      'SKU-COUNT-PIECE',
      'SKU-FINISHED',
      'SKU-FIN-800',
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

    await vm.selectOutputSku(process.id, port.id, 'SKU-RAW');

    expect(errorSpy).toHaveBeenCalledWith('所选 SKU 分类不能作为工序产出');
    expect(port).toEqual(originalPort);
    expect(material.data).toEqual(originalMaterialData);
    expect(vm.history).toHaveLength(originalHistoryLength);
  });

  it('keeps onsite-created output SKUs explicitly semi-finished', async () => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);

    await vm.selectOutputSku(process.id, port.id, '__CREATE__');
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

  it('sends the selected onsite semi-finished base unit to the product API', async () => {
    apiMocks.post.mockResolvedValueOnce({
      success: true,
      data: {
        id: 'SKU-CREATED-COUNT', name: 'Created count semi', unit: '只', productCategory: 'SEMI_FINISHED',
      },
    });
    const vm = await mountEditor();
    const { process, port } = addSecondOutput(vm);

    await vm.selectOutputSku(process.id, port.id, '__CREATE__');
    vm.skuForm.name = 'Created count semi';
    vm.skuForm.unit = '只';
    await vm.confirmCreateSku();

    expect(apiMocks.post).toHaveBeenCalledWith('/F006/product-types', expect.objectContaining({
      name: 'Created count semi', unit: '只', productCategory: 'SEMI_FINISHED',
    }));
    expect(port.unit).toBe('只');
  });

  it('refreshes every downstream input port immediately when an output SKU unit changes', async () => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);
    vm.flowNodes.push({
      id: 'process:downstream',
      position: { x: 900, y: 160 },
      data: {
        kind: 'PROCESS', workProcessId: 'WP-NEXT', processName: 'Next', inputUnit: 'kg', outputUnit: 'kg',
        ports: [{
          id: 'input:downstream', direction: 'INPUT', materialNodeId: material.id, unit: 'kg', ordinal: 0,
        }, {
          id: 'output:downstream', direction: 'OUTPUT', unit: 'kg', ordinal: 0,
        }],
        conversionRule: { mode: 'ACTUAL_WEIGHT' }, reportingRequired: true,
      },
    });
    await vm.selectOutputSku(process.id, port.id, 'SKU-COUNT');

    const downstream = vm.flowNodes.find((node) => node.id === 'process:downstream');
    expect(downstream?.data.inputUnit).toBe('只');
    expect(downstream?.data.ports?.[0].unit).toBe('只');
    expect(downstream?.data.ports?.[0]).not.toHaveProperty('quantityMode');
    expect(downstream?.data.ports?.[0]).not.toHaveProperty('standardQuantity');
    expect(downstream?.data.ports?.[1]).not.toHaveProperty('quantityMode');
    expect(downstream?.data.ports?.[1]).not.toHaveProperty('standardQuantity');
  });

  it('does not retain a stale fixed ratio after switching the output SKU', async () => {
    const vm = await mountEditor();
    const workProcess = vm.workProcessOptions.find((option) => option.id === 'WP-PACK');
    if (!workProcess) throw new Error('Expected work process option');
    workProcess.defaultOutputMaterialKind = 'SEMI_FINISHED';
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    const port = process?.data.ports?.find((candidate) => candidate.direction === 'OUTPUT' && candidate.ordinal === 0);
    if (!process || !port) throw new Error('Expected primary process output');
    await vm.selectOutputSku(process.id, port.id, 'SKU-COUNT');
    const countPort = process.data.ports?.find((candidate) => candidate.id === port.id);
    if (!countPort) throw new Error('Expected rebound count output');
    delete countPort.standardQuantity;
    process.data.conversionRule = { mode: 'FIXED_RATIO', expression: '3 kg = 6 只' };

    await vm.selectOutputSku(process.id, port.id, 'SKU-COUNT-PIECE');

    const reboundPort = process.data.ports?.find((candidate) => candidate.id === port.id);
    expect(reboundPort).toMatchObject({ unit: '件' });
    expect(reboundPort).not.toHaveProperty('quantityMode');
    expect(reboundPort).not.toHaveProperty('standardQuantity');
  });

  it('rejects a duplicate raw material even if a stale picker bypasses candidate filtering', async () => {
    const warnSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined);
    const vm = await mountEditor();
    vm.addStandaloneRaw();
    const secondRaw = vm.flowNodes.filter((node) => node.data.kind === 'RAW_MATERIAL')[1];
    if (!secondRaw) throw new Error('Expected second raw Cell');

    vm.selectRawSku(secondRaw.id, 'RM-PIG');

    expect(warnSpy).toHaveBeenCalledWith('该原料已在当前 Workflow 中使用');
    expect(secondRaw.data.skuId).toBe('');
  });

  it('normalizes every multi-input process to a non-empty per-batch free-choice group', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');

    vm.addInputToProcess(process.id);
    const firstTwoInputIds = process.data.ports
      ?.filter((port) => port.direction === 'INPUT')
      .map((port) => port.id) ?? [];
    process.data.portGroups = [{
      id: 'legacy-exact-one', direction: 'INPUT', label: '互相替代', mode: 'EXACTLY_ONE',
      minSelections: 1, maxSelections: 1, portIds: firstTwoInputIds,
    }];
    vm.addInputToProcess(process.id);

    const inputPorts = process.data.ports?.filter((port) => port.direction === 'INPUT') ?? [];
    expect(inputPorts).toHaveLength(3);
    expect(process.data.portGroups?.filter((group) => group.direction === 'INPUT')).toEqual([{
      id: 'legacy-exact-one',
      direction: 'INPUT',
      label: '批次自由选择',
      mode: 'AT_LEAST_ONE',
      minSelections: 1,
      maxSelections: 3,
      portIds: inputPorts.map((port) => port.id),
    }]);
  });

  it('updates real process master data and refreshes the Cell without losing graph links', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');
    const edgesBefore = JSON.parse(JSON.stringify(vm.flowEdges));

    vm.openQuickEditProcess(process.id);
    Object.assign(vm.processEditForm, {
      processName: '定量包装', processCategory: '包装', unit: 'kg', outputUnit: '盒',
      defaultOutputMaterialKind: 'FINISHED_GOOD', needsInput: true,
    });
    await vm.saveQuickEditProcess();

    expect(apiMocks.updateWorkProcess).toHaveBeenCalledWith('F006', 'WP-PACK', expect.objectContaining({
      processName: '定量包装', processCategory: '包装', unit: 'kg', outputUnit: '盒',
    }));
    expect(process.data.processName).toBe('定量包装');
    expect(vm.flowEdges).toEqual(edgesBefore);
  });

  it('confirms a changed output kind, converts conflicting Cells, and unbinds their SKU and unit', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');
    const outputPort = process.data.ports?.find((port) => port.direction === 'OUTPUT');
    const outputCell = vm.flowNodes.find((node) => node.id === outputPort?.materialNodeId);
    if (!outputPort || !outputCell) throw new Error('Expected output port and Cell');
    expect(outputCell.data).toMatchObject({ kind: 'FINISHED_GOOD', bound: true });

    vm.openQuickEditProcess(process.id);
    vm.processEditForm.defaultOutputMaterialKind = 'SEMI_FINISHED';
    await vm.saveQuickEditProcess();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('将改为半成品并解绑现有 成品 SKU'),
      '确认修改产出类型',
      expect.any(Object),
    );
    expect(apiMocks.updateWorkProcess).toHaveBeenCalledWith('F006', 'WP-PACK', expect.objectContaining({
      defaultOutputMaterialKind: 'SEMI_FINISHED',
    }));
    expect(outputPort).toMatchObject({ materialKind: 'SEMI_FINISHED', unit: '' });
    expect(outputPort.skuId).toBeUndefined();
    expect(outputPort).not.toHaveProperty('quantityMode');
    expect(outputPort).not.toHaveProperty('standardQuantity');
    expect(outputCell.data).toMatchObject({
      kind: 'SEMI_FINISHED', skuId: '', baseUnit: '', bound: false,
    });
  });

  it('does not update process master data or Workflow when output-kind confirmation is cancelled', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel');
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');
    const outputPort = process.data.ports?.find((port) => port.direction === 'OUTPUT');
    const outputCell = vm.flowNodes.find((node) => node.id === outputPort?.materialNodeId);
    if (!outputPort || !outputCell) throw new Error('Expected output port and Cell');

    vm.openQuickEditProcess(process.id);
    vm.processEditForm.defaultOutputMaterialKind = 'SEMI_FINISHED';
    await vm.saveQuickEditProcess();

    expect(apiMocks.updateWorkProcess).not.toHaveBeenCalled();
    expect(outputPort.skuId).toBe('PT-PIG-400');
    expect(outputCell.data).toMatchObject({ kind: 'FINISHED_GOOD', skuId: 'PT-PIG-400', bound: true });
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

async function mountEditor(rawOwnerMode = false): Promise<EditorVm> {
  const wrapper = shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-PIG-400',
      productName: '五香去骨猪蹄 400g',
      canWrite: true,
      rawOwnerMode,
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

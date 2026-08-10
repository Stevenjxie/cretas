import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import { BOM_OVERLAY_PREFIX, stripBomOverlay, stripBomOverlayEdges } from '../bomOverlay';
import type { ProductProcessWorkflowDefinition } from '../types';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getWorkProcessCategories: vi.fn(),
  createWorkProcess: vi.fn(),
  updateWorkProcess: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  getWorkflowBomSyncPreflight: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  publishAndActivateProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
  snapshotProductProcessWorkflow: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: apiMocks.get,
  post: apiMocks.post,
  put: apiMocks.put,
}));

// 2026-08-04: 编辑器加载完定义后会 scheduleBomUnifiedPanelPreload() —— jsdom 里没有
// requestIdleCallback, 于是走 setTimeout(0) 立刻发起 `import('bom-unified/index.vue')`,
// 那条链一路拉到 bom/index.vue → auth store → pinia。组件在 onUnmounted 里能取消的只是
// **定时器**, 已经起飞的动态 import 取消不掉; 它在环境拆除之后才 resolve 就是
// `EnvironmentTeardownError: Cannot load pinia ... after the environment was torn down`,
// 一条未处理拒绝把整个 job 判红 —— 全部用例明明都是绿的 (2026-08-04 挂过 PR #2275 与
// codex/pr-audit-photo-archive-retention-20260804 两个不相干分支)。
//
// 本 spec 用 shallowMount, 压根不渲染 BomUnifiedPanel, 也没有任何一条用例断言预加载 ——
// 把 loader 整个 stub 掉即可: 既不再发起那次真实动态 import, 也顺带省掉一个重 chunk 的转译。
vi.mock('../bomUnifiedPanelLoader', () => ({
  BomUnifiedPanel: { name: 'BomUnifiedPanelStub', render: () => null },
  preloadBomUnifiedPanel: vi.fn(() => Promise.resolve({})),
  scheduleBomUnifiedPanelPreload: vi.fn(() => () => { /* 无定时器可取消 */ }),
}));

vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: apiMocks.getActiveWorkProcesses,
  getWorkProcessCategories: apiMocks.getWorkProcessCategories,
  getProductWorkProcesses: apiMocks.getProductWorkProcesses,
  createWorkProcess: apiMocks.createWorkProcess,
  updateWorkProcess: apiMocks.updateWorkProcess,
}));

vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: apiMocks.getProductProcessWorkflow,
  getProductProcessWorkflowActivation: apiMocks.getProductProcessWorkflowActivation,
  getWorkflowBomSyncPreflight: apiMocks.getWorkflowBomSyncPreflight,
  publishProductProcessWorkflow: apiMocks.publishProductProcessWorkflow,
  publishAndActivateProductProcessWorkflow: apiMocks.publishAndActivateProductProcessWorkflow,
  saveProductProcessWorkflowDraft: apiMocks.saveProductProcessWorkflowDraft,
  snapshotProductProcessWorkflow: apiMocks.snapshotProductProcessWorkflow,
}));

vi.mock('@vue-flow/core', async () => {
  const { defineComponent } = await import('vue');
  return {
    MarkerType: { ArrowClosed: 'arrow-closed' },
    SelectionMode: { Partial: 'partial' },
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
  flowEdges: Array<{
    id: string;
    source: string;
    target: string;
    selected?: boolean;
    markerEnd?: string;
    style?: Record<string, unknown>;
  }>;
  history: unknown[];
  outputSkuOptions: SkuOption[];
  selectedWorkProcessId: string;
  processCreateMode: 'existing' | 'create';
  newProcessForm: { name: string; processCategory: string; outputKind: 'SEMI_FINISHED' | 'FINISHED_GOOD' };
  openAddProcess: (materialNodeId: string) => void;
  confirmAddProcess: () => void;
  confirmCreateAndAddProcess: () => Promise<void>;
  addOutputToProcess: (processId: string) => void;
  addInputToProcess: (processId: string) => void;
  rawInputDialog: {
    visible: boolean;
    processId: string;
    mainMaterialNodeId: string;
    mainMaterialName: string;
    skuId: string;
    relation: '' | 'SUBSTITUTE' | 'PARALLEL';
  };
  confirmAddRawInput: () => void;
  derivedWorkflowClassification: {
    type: string; label: string; rootInputCount: number; terminalOutputCount: number;
  };
  updateProcessData: (processId: string, patch: Record<string, unknown>) => void;
  addStandaloneRaw: () => void;
  selectOutputSku: (processId: string, portId: string, skuId: string) => Promise<void>;
  selectMaterialSku: (materialNodeId: string, skuId: string) => Promise<void>;
  selectRawSku: (materialNodeId: string, skuId: string) => void;
  openQuickEditProcess: (processNodeId: string) => void;
  saveQuickEditProcess: () => Promise<void>;
  processEditForm: {
    processName: string; processCategory: string;
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
  saveDraft: (options?: { silent?: boolean; preserveHistory?: boolean }) => Promise<boolean>;
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
  outputRole?: 'MAIN' | 'CO_PRODUCT' | 'BY_PRODUCT' | null;
  costAllocationRatio?: number | null;
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
  { id: 'PT-PIG-400', name: '五香去骨猪蹄 400g', unit: 'kg', productCategory: 'FINISHED_PRODUCT' },
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

// 挂载出来的实例必须卸载 —— 编辑器把「取消防抖 autosave」挂在 onUnmounted 上
// (scheduleAutoSave 排的是 AUTO_SAVE_DELAY=2500ms 的定时器, 只有 invalidatePendingAutoSave 会清)。
// 不卸载 = 每个实例留一个活定时器: 实测本文件跑完时残留 67 个定时器, 其中 26 个是 2500ms 的
// autosave。它们在测试文件结束后才触发, 落进 vitest 环境拆卸窗口时会 console.error, 而日志要
// 经 onUserConsoleLog RPC 回主进程 —— 通道正在关闭就报
// `EnvironmentTeardownError: Closing rpc while "onUserConsoleLog" was pending`,
// 表现是**单测全过但 npm test exit 1**, 且只在整套跑时偶发(单跑文件结束得早, 定时器随环境一起丢弃)。
const mountedWrappers: VueWrapper[] = [];

afterEach(() => {
  while (mountedWrappers.length) mountedWrappers.pop()?.unmount();
});

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
          { id: 'RM-DUCK', name: '鸭肉原料', unit: 'kg', category: '原料' },
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
    apiMocks.getWorkProcessCategories.mockResolvedValue({ success: true, data: ['前处理', '加工', '包装', 'PACKING'] });
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
        revisionId: 71,
        revisionHash: 'revision-current',
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
    apiMocks.getWorkflowBomSyncPreflight.mockResolvedValue({
      success: true,
      data: {
        classification: 'READY',
        activeBomVersion: 1,
        syncDraftVersion: null,
        activeBomWorkflowRevisionId: 71,
        targetWorkflowRevisionId: 71,
        preservedItems: [],
        automaticMappings: [],
        missingItems: [],
        conflicts: [],
        canCompleteAutomatically: true,
      },
    });
  });

  it('adds the process, derived finished output, and both edges in one undo snapshot', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();

    // must-fix #7: mutate() 现在会重新派生 BOM 浮层 —— 新加的 PROCESS/FINISHED_GOOD
    // 节点各带一个 bomAuxiliary/bomPackaging cell 与一条投影边, 这份用例只关心"真实
    // 工艺图"的增删, 用 stripBomOverlay(Edges) 滤掉浮层, 同 line ~456 已有的写法。
    expect(stripBomOverlay(vm.flowNodes)).toHaveLength(3);
    expect(stripBomOverlayEdges(vm.flowEdges)).toHaveLength(2);
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

  it('projects a unique raw material name into the same Cell identity without autosaving on page load', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValueOnce({
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
          data: { name: '猪蹄原料', skuId: '', skuCode: '待绑定原料 SKU', bound: false, baseUnit: '' },
        }],
        edges: [],
        viewport: { x: 0, y: 0, zoom: 1 },
      },
    });

    const vm = await mountEditor();

    expect(vm.flowNodes.find((node) => node.id === 'raw')?.data).toMatchObject({
      name: '猪蹄原料',
      skuId: 'RM-PIG',
      skuCode: 'RM-PIG',
      baseUnit: 'kg',
      bound: true,
    });
    expect(vm.dirty).toBe(false);
    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
  });

  it('serializes multi-output role and allocation fields in the real editor save payload', async () => {
    apiMocks.saveProductProcessWorkflowDraft.mockImplementation((
      _factoryId: string,
      _productTypeId: string,
      definition: ProductProcessWorkflowDefinition,
    ) => Promise.resolve({
      success: true,
      data: { ...structuredClone(definition), id: 1, lockVersion: 1 },
    }));
    const vm = await mountEditor();
    const { process, port: secondPort } = addSecondOutput(vm);
    await vm.selectOutputSku(process.id, secondPort.id, 'SKU-FINISHED');
    const outputPorts = (process.data.ports ?? []).filter((port) => port.direction === 'OUTPUT')
      .sort((left, right) => left.ordinal - right.ordinal)
      .map((port, index) => ({
        ...port,
        outputRole: index === 0 ? 'MAIN' as const : 'CO_PRODUCT' as const,
        costAllocationRatio: index === 0 ? 60 : 40,
      }));
    vm.updateProcessData(process.id, {
      ports: [
        ...(process.data.ports ?? []).filter((port) => port.direction === 'INPUT'),
        ...outputPorts,
      ],
    });

    await vm.saveDraft();
    expect(apiMocks.saveProductProcessWorkflowDraft).toHaveBeenCalledTimes(1);

    const payload = (
      apiMocks.saveProductProcessWorkflowDraft.mock.calls.at(-1)?.[2]
    ) as unknown as ProductProcessWorkflowDefinition;
    const savedProcess = payload.nodes.find((node) => node.id === process.id);
    expect(savedProcess?.data.ports).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: outputPorts[0].id, outputRole: 'MAIN', costAllocationRatio: 60 }),
      expect.objectContaining({ id: outputPorts[1].id, outputRole: 'CO_PRODUCT', costAllocationRatio: 40 }),
    ]));
  });

  it('blocks the atomic publish when the authoritative preflight reports no active BOM', async () => {
    apiMocks.saveProductProcessWorkflowDraft.mockImplementation((
      _factoryId: string,
      _productTypeId: string,
      definition: ProductProcessWorkflowDefinition,
    ) => Promise.resolve({
      success: true,
      data: {
        ...structuredClone(definition),
        id: 1,
        lockVersion: 1,
        revisionId: 72,
        revisionHash: 'revision-saved',
      },
    }));
    apiMocks.getWorkflowBomSyncPreflight.mockResolvedValue({
      success: true,
      data: {
        classification: 'USER_INPUT_REQUIRED',
        activeBomVersion: null,
        syncDraftVersion: null,
        activeBomWorkflowRevisionId: null,
        targetWorkflowRevisionId: 72,
        preservedItems: [],
        automaticMappings: [],
        missingItems: [{
          code: 'WORKFLOW_ACTIVE_BOM_REQUIRED',
          materialTypeId: null,
          materialName: null,
          processNodeId: null,
          field: 'bom',
          message: '当前产品没有生效 BOM',
          action: '请先完成 BOM 配置',
        }],
        conflicts: [],
        canCompleteAutomatically: false,
      },
    });
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    expect(vm.bomMissingProducts).toEqual([{ id: 'PT-PIG-400', name: '五香去骨猪蹄 400g' }]);
    expect(vm.publishDisabledReason).toBe('');

    await vm.publishWorkflow();

    expect(apiMocks.saveProductProcessWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(apiMocks.getWorkflowBomSyncPreflight).toHaveBeenCalledTimes(1);
    expect(apiMocks.publishAndActivateProductProcessWorkflow).not.toHaveBeenCalled();
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
    // Task6: flowNodes/flowEdges 现在混着 BOM 浮层 cell(辅料/包材, 挂在 PROCESS/
    // FINISHED_GOOD 节点上, 见 refreshBomOverlay), 这份用例只关心"真实工艺图"的
    // 增删撤销, 用 stripBomOverlay(Edges) 滤掉浮层, 不受它的多少影响。
    const beforeDelete = stripBomOverlay(vm.flowNodes).length;
    const historyBeforeDelete = vm.history.length;
    const raw = vm.flowNodes.find((node) => node.id === 'raw');
    if (!raw) throw new Error('Expected raw node');
    raw.selected = true;

    vm.removeSelectedElements();
    await flushPromises();

    expect(stripBomOverlay(vm.flowNodes)).toHaveLength(beforeDelete - 1);
    expect(stripBomOverlayEdges(vm.flowEdges)).toHaveLength(1);
    expect(vm.flowEdges.some((edge) => edge.source === 'raw' || edge.target === 'raw')).toBe(false);
    expect(vm.history).toHaveLength(historyBeforeDelete + 1);
    vm.undo();
    expect(stripBomOverlay(vm.flowNodes)).toHaveLength(beforeDelete);
    expect(stripBomOverlayEdges(vm.flowEdges)).toHaveLength(2);
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

  it('requires a taxonomy category and does not submit a unit for onsite process creation', async () => {
    const vm = await mountEditor();
    const raw = vm.flowNodes.find((node) => node.id === 'raw');
    if (!raw) throw new Error('Expected raw source');
    raw.data.baseUnit = 'g';
    vm.openAddProcess('raw');
    vm.processCreateMode = 'create';
    vm.newProcessForm.name = '现场切分';
    vm.newProcessForm.processCategory = '加工';

    await vm.confirmCreateAndAddProcess();

    expect(apiMocks.createWorkProcess).toHaveBeenCalledWith('F006', expect.objectContaining({
      processName: '现场切分', processCategory: '加工',
    }));
    expect(apiMocks.createWorkProcess.mock.calls[0]?.[1]).not.toHaveProperty('unit');
    expect(apiMocks.createWorkProcess.mock.calls[0]?.[1]).not.toHaveProperty('outputUnit');
  });

  it('binds a semi-finished SKU on both the port and material Cell', async () => {
    const vm = await mountEditor();
    const { process, port, material } = addSecondOutput(vm);
    const renderedMaterialData = material.data;

    await vm.selectOutputSku(process.id, port.id, 'SKU-SEMI');

    expect(port).toMatchObject({ skuId: 'SKU-SEMI', materialKind: 'SEMI_FINISHED' });
    expect(material.data).toBe(renderedMaterialData);
    expect(material.data).toMatchObject({ skuId: 'SKU-SEMI', kind: 'SEMI_FINISHED', bound: true });
  });

  it('refreshes the connected finished Cell identity when its process output SKU changes', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    const port = process?.data.ports?.find((candidate) => candidate.direction === 'OUTPUT');
    const material = vm.flowNodes.find((node) => node.id === port?.materialNodeId);
    if (!process || !port || !material) throw new Error('Expected primary finished output');
    const renderedMaterialData = material.data;

    await vm.selectOutputSku(process.id, port.id, 'SKU-FIN-800');

    expect(port).toMatchObject({
      skuId: 'SKU-FIN-800',
      materialName: 'Finished 800g',
      materialKind: 'FINISHED_GOOD',
      unit: '盒',
    });
    expect(material.data).toBe(renderedMaterialData);
    expect(renderedMaterialData).toMatchObject({
      name: 'Finished 800g',
      skuId: 'SKU-FIN-800',
      skuCode: 'SKU-FIN-800',
      kind: 'FINISHED_GOOD',
      baseUnit: '盒',
      bound: true,
    });
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
    // 2026-08-10: 这道工序已经有原料, 加第二个原料先问关系 —— 走完弹窗才落图
    vm.addInputToProcess(process.id);
    vm.rawInputDialog.skuId = 'RM-CHICKEN';
    vm.rawInputDialog.relation = 'PARALLEL';
    vm.confirmAddRawInput();

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
      'PT-PIG-400',
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

  it('changes the current Workflow output snapshot when a process output selects a finished SKU', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const vm = await mountEditor();
    const workProcess = vm.workProcessOptions.find((option) => option.id === 'WP-PACK');
    if (!workProcess) throw new Error('Expected work process option');
    workProcess.defaultOutputMaterialKind = 'SEMI_FINISHED';
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    const port = process?.data.ports?.find((candidate) => candidate.direction === 'OUTPUT' && candidate.ordinal === 0);
    const material = vm.flowNodes.find((node) => node.id === port?.materialNodeId);
    if (!process || !port || !material) throw new Error('Expected primary process output');

    await vm.selectOutputSku(process.id, port.id, 'SKU-FINISHED');
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('将这个产出 Cell 改为成品并绑定所选 SKU'),
      '产出类型不一致',
      expect.any(Object),
    );
    expect(port).toMatchObject({
      materialKind: 'FINISHED_GOOD', skuId: 'SKU-FINISHED', unit: 'kg',
    });
    expect(material.data).toMatchObject({
      kind: 'FINISHED_GOOD', skuId: 'SKU-FINISHED', name: 'Finished output', bound: true,
    });
    expect(workProcess.defaultOutputMaterialKind).toBe('SEMI_FINISHED');
    expect(apiMocks.updateWorkProcess).not.toHaveBeenCalled();
  });

  it('reads a legacy output Cell snapshot before the process catalog default', async () => {
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
    delete port.materialKind;
    workProcess.defaultOutputMaterialKind = 'FINISHED_GOOD';

    vm.openQuickEditProcess(process.id);

    expect(vm.processEditForm.defaultOutputMaterialKind).toBe('SEMI_FINISHED');
  });

  it('changes the same Workflow snapshot when a semi-finished Cell selects a finished SKU', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const vm = await mountEditor();
    const workProcess = vm.workProcessOptions.find((option) => option.id === 'WP-PACK');
    if (!workProcess) throw new Error('Expected work process option');
    workProcess.defaultOutputMaterialKind = 'SEMI_FINISHED';
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    const port = process?.data.ports?.find((candidate) => candidate.direction === 'OUTPUT' && candidate.ordinal === 0);
    const material = vm.flowNodes.find((node) => node.id === port?.materialNodeId);
    if (!port || !material) throw new Error('Expected semi-finished output Cell');

    await vm.selectMaterialSku(material.id, 'SKU-FINISHED');
    await flushPromises();

    expect(port).toMatchObject({ materialKind: 'FINISHED_GOOD', skuId: 'SKU-FINISHED' });
    expect(material.data).toMatchObject({ kind: 'FINISHED_GOOD', skuId: 'SKU-FINISHED', bound: true });
    expect(workProcess.defaultOutputMaterialKind).toBe('SEMI_FINISHED');
    expect(apiMocks.updateWorkProcess).not.toHaveBeenCalled();
  });

  it('keeps the Workflow unchanged when an output-kind selection is cancelled', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel');
    const vm = await mountEditor();
    const workProcess = vm.workProcessOptions.find((option) => option.id === 'WP-PACK');
    if (!workProcess) throw new Error('Expected work process option');
    workProcess.defaultOutputMaterialKind = 'SEMI_FINISHED';
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    const port = process?.data.ports?.find((candidate) => candidate.direction === 'OUTPUT' && candidate.ordinal === 0);
    const material = vm.flowNodes.find((node) => node.id === port?.materialNodeId);
    if (!process || !port || !material) throw new Error('Expected primary process output');

    await vm.selectOutputSku(process.id, port.id, 'SKU-FINISHED');
    await flushPromises();

    expect(port).toMatchObject({ materialKind: 'SEMI_FINISHED' });
    expect(port.skuId).toBe('');
    expect(material.data).toMatchObject({ kind: 'SEMI_FINISHED', skuId: '', bound: false });
    expect(apiMocks.updateWorkProcess).not.toHaveBeenCalled();
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

  it('preserves existing input groups instead of rewriting them to AT_LEAST_ONE', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');

    vm.addInputToProcess(process.id);
    vm.rawInputDialog.skuId = 'RM-CHICKEN';
    vm.rawInputDialog.relation = 'PARALLEL';
    vm.confirmAddRawInput();
    const firstTwoInputIds = process.data.ports
      ?.filter((port) => port.direction === 'INPUT')
      .map((port) => port.id) ?? [];
    process.data.portGroups = [{
      id: 'legacy-exact-one', direction: 'INPUT', label: '互相替代', mode: 'EXACTLY_ONE',
      minSelections: 1, maxSelections: 1, portIds: firstTwoInputIds,
    }];
    vm.addInputToProcess(process.id);
    vm.rawInputDialog.skuId = 'RM-DUCK';
    vm.rawInputDialog.relation = 'PARALLEL';
    vm.confirmAddRawInput();

    expect(process.data.ports?.filter((port) => port.direction === 'INPUT')).toHaveLength(3);
    expect(process.data.portGroups?.filter((group) => group.direction === 'INPUT')).toEqual([{
      id: 'legacy-exact-one',
      direction: 'INPUT',
      label: '互相替代',
      mode: 'EXACTLY_ONE',
      minSelections: 1,
      maxSelections: 1,
      portIds: firstTwoInputIds,
    }]);
  });

  it('updates process master fields and refreshes the Cell without losing graph links', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();
    const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
    if (!process) throw new Error('Expected process node');
    const edgesBefore = JSON.parse(JSON.stringify(vm.flowEdges));

    vm.openQuickEditProcess(process.id);
    Object.assign(vm.processEditForm, {
      processName: '定量包装', processCategory: '包装',
      defaultOutputMaterialKind: 'FINISHED_GOOD', needsInput: true,
    });
    await vm.saveQuickEditProcess();

    expect(apiMocks.updateWorkProcess).toHaveBeenCalledWith('F006', 'WP-PACK', expect.objectContaining({
      processName: '定量包装', processCategory: '包装',
    }));
    expect(apiMocks.updateWorkProcess.mock.calls.at(-1)?.[2]).not.toHaveProperty('unit');
    expect(apiMocks.updateWorkProcess.mock.calls.at(-1)?.[2]).not.toHaveProperty('outputUnit');
    expect(apiMocks.updateWorkProcess.mock.calls.at(-1)?.[2]).not.toHaveProperty('defaultOutputMaterialKind');
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
    expect(apiMocks.updateWorkProcess).not.toHaveBeenCalled();
    expect(vm.workProcessOptions.find((option) => option.id === 'WP-PACK')?.defaultOutputMaterialKind)
      .toBe('FINISHED_GOOD');
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

  // BOM 浮层的业务数据仍是只读投影，但布局位置允许用户调整。拖动不能把 Workflow
  // 标成 dirty；点选/删除仍必须被挡住，避免把浮层混进工艺定义。
  // 混进 selectedCellIds(污染「已选 N 个」计数与发给 AI 的 selectedNodeContext); 走
  // removeNode/removeSelectedElements 被当真删除。三条路径共享同一个根因(mutate() 里
  // 手动拼 flowNodes.value 时没给浮层节点设 draggable/selectable/deletable=false), 所以
  // 也在一份用例里一起验。挂在这个 describe 内部(而不是顶层新开一个)是为了复用
  // 上面这个 describe 的 beforeEach(否则 apiMocks.get/getProductProcessWorkflow 等
  // 都是未实现的裸 vi.fn(), mountEditor() 会在 catalog/definition/activation 加载阶段
  // 直接炸掉)。
  it('浮层 cell 可拖动且保留位置，但不产生 Workflow dirty，也不可选删', async () => {
    const vm = await mountEditor();
    vm.openAddProcess('raw');
    vm.selectedWorkProcessId = 'WP-PACK';
    vm.confirmAddProcess();

    const auxCell = vm.flowNodes.find((node) => node.id.startsWith(`${BOM_OVERLAY_PREFIX}aux:`));
    if (!auxCell) throw new Error('Expected an aux overlay cell after adding a process (must-fix #7 wires mutate() to refreshBomOverlay)');

    // 1) 节点级标志本身
    expect(auxCell).toMatchObject({ draggable: true, selectable: false, deletable: false });

    // 2) 拖动只改浮层的会话布局，不会把 dirty 翻回 true，也不会在 BOM 重载时跳回去。
    vm.dirty = false;
    const movedPosition = { x: auxCell.position.x + 80, y: auxCell.position.y + 48 };
    vm.onNodeDragStart({ node: auxCell });
    vm.onNodeDragStop({ node: { ...auxCell, position: movedPosition } });
    expect(vm.dirty).toBe(false);
    expect(vm.flowNodes.find((node) => node.id === auxCell.id)?.position).toEqual(movedPosition);
    vm.refreshBomOverlay();
    expect(vm.flowNodes.find((node) => node.id === auxCell.id)?.position).toEqual(movedPosition);
    const auxEdge = vm.flowEdges.find((edge) => edge.source === auxCell.id);
    if (!auxEdge) throw new Error('Expected the auxiliary projection edge to stay attached');
    expect(auxEdge).toMatchObject({
      sourceHandle: 'bom-aux-out',
      targetHandle: 'bom-aux-in',
      markerEnd: 'arrow-closed',
      style: { stroke: '#1b65a8', strokeWidth: 2 },
      selectable: false,
      deletable: false,
      updatable: false,
    });
    const edgeCount = vm.flowEdges.length;
    vm.onEdgeClick({ edge: auxEdge });
    vm.removeEdgeById(auxEdge.id);
    expect(vm.flowEdges).toHaveLength(edgeCount);
    expect(vm.flowEdges.some((edge) => edge.id === auxEdge.id)).toBe(true);

    // 3) 点击浮层 cell 不会把它选中、不会混进 selectedCellIds/selectedNodeContext
    vm.onNodeClick({ node: auxCell });
    expect(vm.selectedNodeContext.selectedNodeIds).not.toContain(auxCell.id);
    expect(vm.flowNodes.find((node) => node.id === auxCell.id)?.selected).toBeFalsy();

    // 4) removeNode 对浮层 cell 是纯 no-op: 不弹确认框, 节点原样还在
    // (确认框 resolve 成功仅为万一测到的是移除前的代码时不挂起 promise, 不影响本用例
    // 的断言重点——重点是 confirm 压根不该被调用)
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const countBeforeRemove = vm.flowNodes.length;
    vm.removeNode(auxCell.id);
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(vm.flowNodes).toHaveLength(countBeforeRemove);
    expect(vm.flowNodes.some((node) => node.id === auxCell.id)).toBe(true);

    // 5) removeSelectedElements 同理: 强行把浮层 cell 标记为 selected 也不会被批量删除路径带走
    const target = vm.flowNodes.find((node) => node.id === auxCell.id);
    if (!target) throw new Error('Expected aux cell to still be present');
    target.selected = true;
    vm.removeSelectedElements();
    expect(vm.flowNodes.some((node) => node.id === auxCell.id)).toBe(true);
  });

  // ── 新增原料 Cell 的关系弹窗 (2026-08-10) ──────────────────────────────────
  //
  // ⚠️ 这一组用例**断言渲染结果**, 不断言 `vm.rawInputDialog.visible`。
  //    本轮已经栽过一次: 弹窗的 CSS `display` 盖过 `hidden` 属性 —— 状态改了、
  //    弹窗关不掉, 而只看状态变量的测试全绿。所以一律 `wrapper.find(...).exists()`。

  it('已有原料时点「加原料」先弹关系弹窗, 不直接建节点', async () => {
    const wrapper = await mountEditorWrapper();
    const vm = wrapper.vm as unknown as EditorVm;
    const process = addProcessOffRaw(vm);
    const inputsBefore = process.data.ports?.filter((port) => port.direction === 'INPUT').length ?? 0;

    vm.addInputToProcess(process.id);
    await wrapper.vm.$nextTick();

    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(true);
    expect(process.data.ports?.filter((port) => port.direction === 'INPUT'))
      .toHaveLength(inputsBefore);
    // 生产用原料统一 kg, 几只一箱属于原料建档时的基本规格 —— 这里不该问换算系数
    expect(wrapper.find('[data-testid="raw-input-modal"]').text()).not.toContain('换算');
  });

  it('两项没选齐时「确定」是禁用的', async () => {
    const wrapper = await mountEditorWrapper();
    const vm = wrapper.vm as unknown as EditorVm;
    const process = addProcessOffRaw(vm);

    // ⚠️ 原生 button 的 disabled 渲染成**空字符串**, `toBeTruthy()` 会永远失败 ——
    //    只能断言属性在不在。用 el-button 更糟: 未解析组件上 :disabled="false"
    //    会渲染成 disabled="false"(字符串, JS 真值), 断言两个方向都分不出来。
    vm.addInputToProcess(process.id);
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="raw-input-confirm"]').attributes())
      .toHaveProperty('disabled');

    vm.rawInputDialog.skuId = 'RM-CHICKEN';
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="raw-input-confirm"]').attributes())
      .toHaveProperty('disabled');

    vm.rawInputDialog.relation = 'SUBSTITUTE';
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="raw-input-confirm"]').attributes())
      .not.toHaveProperty('disabled');
  });

  it('选「替代料」确定后, 新原料 Cell 带 substituteOfNodeId 指向主料', async () => {
    const wrapper = await mountEditorWrapper();
    const vm = wrapper.vm as unknown as EditorVm;
    const process = addProcessOffRaw(vm);

    vm.addInputToProcess(process.id);
    vm.rawInputDialog.skuId = 'RM-CHICKEN';
    vm.rawInputDialog.relation = 'SUBSTITUTE';
    vm.confirmAddRawInput();
    await wrapper.vm.$nextTick();

    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(false);
    const created = newestRawMaterial(vm);
    expect(created.data.substituteOfNodeId).toBe('raw');
    expect(created.data).toMatchObject({ skuId: 'RM-CHICKEN', name: '鸡肉原料', bound: true });
    expect(process.data.ports?.filter((port) => port.direction === 'INPUT')).toHaveLength(2);
  });

  it('选「另一种投入」确定后, 新原料 Cell 不带 substituteOfNodeId', async () => {
    const wrapper = await mountEditorWrapper();
    const vm = wrapper.vm as unknown as EditorVm;
    const process = addProcessOffRaw(vm);

    vm.addInputToProcess(process.id);
    vm.rawInputDialog.skuId = 'RM-CHICKEN';
    vm.rawInputDialog.relation = 'PARALLEL';
    vm.confirmAddRawInput();
    await wrapper.vm.$nextTick();

    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(false);
    const created = newestRawMaterial(vm);
    expect(created.data.substituteOfNodeId).toBeUndefined();
    expect(process.data.ports?.filter((port) => port.direction === 'INPUT')).toHaveLength(2);
  });

  it('研判收得到替代关系 —— 两个根声明替代后合成一个逻辑投入', async () => {
    const vm = await mountEditor();

    const independent = classifyGraph(vm, [
      topoNode('r1', 'RAW_MATERIAL'), topoNode('r2', 'RAW_MATERIAL'),
      topoNode('p', 'PROCESS'), topoNode('f1', 'FINISHED_GOOD'), topoNode('f2', 'FINISHED_GOOD'),
    ], [
      { id: 'e1', source: 'r1', target: 'p' }, { id: 'e2', source: 'r2', target: 'p' },
      { id: 'e3', source: 'p', target: 'f1' }, { id: 'e4', source: 'p', target: 'f2' },
    ]);
    expect(independent).toMatchObject({ type: 'JOINT_PRODUCTION', rootInputCount: 2 });

    const substituted = classifyGraph(vm, [
      topoNode('r1', 'RAW_MATERIAL'),
      topoNode('r2', 'RAW_MATERIAL', { substituteOfNodeId: 'r1' }),
      topoNode('p', 'PROCESS'), topoNode('f1', 'FINISHED_GOOD'), topoNode('f2', 'FINISHED_GOOD'),
    ], [
      { id: 'e1', source: 'r1', target: 'p' }, { id: 'e2', source: 'r2', target: 'p' },
      { id: 'e3', source: 'p', target: 'f1' }, { id: 'e4', source: 'p', target: 'f2' },
    ]);
    expect(substituted).toMatchObject({ type: 'RAW_SPLIT', rootInputCount: 1 });
  });

  it('研判收得到副产标记 —— 主成品 + 副产是单产出', async () => {
    const vm = await mountEditor();

    const result = classifyGraph(vm, [
      topoNode('r1', 'RAW_MATERIAL'), topoNode('p', 'PROCESS'),
      topoNode('f1', 'FINISHED_GOOD'),
      topoNode('fby', 'FINISHED_GOOD', { isByproduct: true }),
    ], [
      { id: 'e1', source: 'r1', target: 'p' },
      { id: 'e2', source: 'p', target: 'f1' }, { id: 'e3', source: 'p', target: 'fby' },
    ]);

    expect(result).toMatchObject({ type: 'PRODUCT', terminalOutputCount: 1 });
  });

  it('该工序还没有任何原料时不弹窗, 直接建独立投入', async () => {
    const wrapper = await mountEditorWrapper();
    const vm = wrapper.vm as unknown as EditorVm;
    const process = addProcessOffRaw(vm);
    // 把唯一的上游物料改成半成品 —— 这道工序于是没有任何原料 Cell 可供替代
    const upstream = vm.flowNodes.find((node) => node.id === 'raw');
    if (!upstream) throw new Error('Expected the raw anchor Cell');
    upstream.data.kind = 'SEMI_FINISHED';

    vm.addInputToProcess(process.id);
    await wrapper.vm.$nextTick();

    expect(wrapper.find('[data-testid="raw-input-modal"]').exists()).toBe(false);
    expect(process.data.ports?.filter((port) => port.direction === 'INPUT')).toHaveLength(2);
  });
});

// 🔴 分类器认得 isByproduct / substituteOfNodeId, **不等于**编辑器把它们传得到。
//    漏传的话两条规则在真实画布上一次都不会生效, 而 workflowClassification.spec.ts
//    直接构造入参、照样全绿 —— 那就是「闸在找错地方」。这两条用例走编辑器的真实入参路径。
function classifyGraph(vm: EditorVm, nodes: EditorNode[], edges: Array<{
  id: string; source: string; target: string;
}>): EditorVm['derivedWorkflowClassification'] {
  vm.flowNodes = nodes;
  vm.flowEdges = edges;
  return vm.derivedWorkflowClassification;
}

function topoNode(id: string, kind: string, data: Record<string, unknown> = {}): EditorNode {
  return { id, position: { x: 0, y: 0 }, data: { kind, skuId: id, ...data } };
}

function addProcessOffRaw(vm: EditorVm): EditorNode {
  vm.openAddProcess('raw');
  vm.selectedWorkProcessId = 'WP-PACK';
  vm.confirmAddProcess();
  const process = vm.flowNodes.find((node) => node.data.kind === 'PROCESS');
  if (!process) throw new Error('Expected process node');
  return process;
}

function newestRawMaterial(vm: EditorVm): EditorNode {
  const raws = vm.flowNodes.filter((node) => node.data.kind === 'RAW_MATERIAL');
  const created = raws.at(-1);
  if (!created) throw new Error('Expected a newly created raw material Cell');
  return created;
}

async function mountEditorWrapper(rawOwnerMode = false): Promise<VueWrapper> {
  const wrapper: VueWrapper = shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-PIG-400',
      productName: '五香去骨猪蹄 400g',
      canWrite: true,
      rawOwnerMode,
    },
  });
  mountedWrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

async function mountEditor(rawOwnerMode = false): Promise<EditorVm> {
  const wrapper = await mountEditorWrapper(rawOwnerMode);
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

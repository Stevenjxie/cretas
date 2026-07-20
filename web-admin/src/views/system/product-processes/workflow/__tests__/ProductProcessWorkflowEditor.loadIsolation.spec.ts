import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import type { ProductProcessWorkflowDefinition } from '../types';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  createWorkProcess: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getWorkProcessCategories: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  updateWorkProcess: vi.fn(),
  updateWorkProcessOutputKind: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
  snapshotProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  listProductProcessWorkflowVersions: vi.fn(),
  getProductProcessWorkflowVersion: vi.fn(),
  fitView: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: apiMocks.get,
  post: apiMocks.post,
}));

vi.mock('@/api/processProduction', () => ({
  createWorkProcess: apiMocks.createWorkProcess,
  getActiveWorkProcesses: apiMocks.getActiveWorkProcesses,
  getWorkProcessCategories: apiMocks.getWorkProcessCategories,
  getProductWorkProcesses: apiMocks.getProductWorkProcesses,
  updateWorkProcess: apiMocks.updateWorkProcess,
  updateWorkProcessOutputKind: apiMocks.updateWorkProcessOutputKind,
}));

vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: apiMocks.getProductProcessWorkflow,
  getProductProcessWorkflowActivation: apiMocks.getProductProcessWorkflowActivation,
  publishProductProcessWorkflow: apiMocks.publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft: apiMocks.saveProductProcessWorkflowDraft,
  snapshotProductProcessWorkflow: apiMocks.snapshotProductProcessWorkflow,
  listProductProcessWorkflowVersions: apiMocks.listProductProcessWorkflowVersions,
  getProductProcessWorkflowVersion: apiMocks.getProductProcessWorkflowVersion,
}));

vi.mock('@vue-flow/core', () => ({
  MarkerType: { ArrowClosed: 'arrow-closed' },
  SelectionMode: { Partial: 'partial' },
  VueFlow: defineComponent({ name: 'VueFlow', template: '<div />' }),
  useVueFlow: () => ({
    fitView: apiMocks.fitView,
    getViewport: () => ({ x: 0, y: 0, zoom: 1 }),
    setViewport: vi.fn(),
  }),
}));

vi.mock('@vue-flow/background', () => ({
  Background: defineComponent({ name: 'Background', template: '<div />' }),
}));

vi.mock('@vue-flow/controls', () => ({
  Controls: defineComponent({ name: 'Controls', template: '<div />' }),
}));

interface EditorVm {
  canEdit: boolean;
  creatingSku: boolean;
  dirty: boolean;
  flowNodes: Array<{ id: string; data: Record<string, unknown> }>;
  history: ProductProcessWorkflowDefinition[];
  loading: boolean;
  rawMaterialOptions: Array<{ id: string }>;
  publishBindingErrors: Array<{ code: string; nodeId?: string }>;
  publishBindingAttentionNodeIds: Set<string>;
  skuOptions: Array<{ id: string }>;
  workProcessOptions: Array<{ id: string }>;
  addStandaloneRaw: () => void;
  applyWorkflowAIDraft: (
    payload: Record<string, unknown>,
    sourceIdentity?: { factoryId: string; productTypeId: string },
  ) => Promise<void>;
  publishWorkflow: () => Promise<void>;
  saveDraft: () => Promise<boolean>;
  currentDefinition: () => ProductProcessWorkflowDefinition;
  onViewportChangeEnd: (viewport: { x: number; y: number; zoom: number }) => void;
  confirmCreateSku: () => Promise<void>;
  selectOutputSku: (processId: string, portId: string, skuId: string) => void;
  selectRawSku: (nodeId: string, skuId: string) => void;
  onNodeClick: (event: { node: { id: string } }) => void;
  skuDialogVisible: boolean;
}

interface ApiResponse {
  success: boolean;
  data: ProductProcessWorkflowDefinition | null;
}

describe('ProductProcessWorkflowEditor load identity isolation', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    apiMocks.get.mockImplementation((url: string) => Promise.resolve({
      success: true,
      data: url.includes('/bom/recipes/by-product/')
        ? { id: 'R-1', items: [{ id: 1, materialTypeId: 'SKU-RAW', materialName: 'Raw', unit: 'kg' }] }
        : url.includes('/product-types')
        ? { content: [testSku('PT-A'), testSku('PT-B')] }
        : url.includes('/raw-material-types')
          ? [testSku('SKU-RAW'), testSku('SKU:PT-A'), testSku('SKU:PT-B')]
            .map((item) => ({ ...item, category: '主材' }))
          : [],
    }));
    apiMocks.getActiveWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getWorkProcessCategories.mockResolvedValue({ success: true, data: [] });
    apiMocks.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({ success: true, data: null });
    apiMocks.listProductProcessWorkflowVersions.mockResolvedValue({ success: true, data: [] });
    apiMocks.getProductProcessWorkflowVersion.mockResolvedValue({ success: true, data: null });
    apiMocks.saveProductProcessWorkflowDraft.mockImplementation(
      (_factoryId: string, _productTypeId: string, definition: ProductProcessWorkflowDefinition) => (
        Promise.resolve({ success: true, data: definition })
      ),
    );
    apiMocks.publishProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm');
  });

  it('focuses and marks every unbound SKU Cell, then clears each marker as it is handled', async () => {
    const unbound = publishableDefinition('PT-A');
    unbound.nodes = unbound.nodes.map((node) => (
      node.kind === 'PROCESS'
        ? node
        : { ...node, data: { ...node.data, skuId: '', bound: false } }
    ));
    apiMocks.getProductProcessWorkflow.mockResolvedValue({ success: true, data: unbound });
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);

    await vm.publishWorkflow();

    expect(vm.publishBindingErrors.map((error) => error.nodeId)).toEqual(['raw', 'finished']);
    expect([...vm.publishBindingAttentionNodeIds]).toEqual(['raw', 'finished']);
    expect(apiMocks.fitView).toHaveBeenCalledWith(expect.objectContaining({
      nodes: ['raw'],
    }));
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();

    vm.onNodeClick({ node: { id: 'raw' } });
    expect([...vm.publishBindingAttentionNodeIds]).toEqual(['finished']);
    expect(vm.publishBindingErrors.map((error) => error.nodeId)).toEqual(['raw', 'finished']);

    vm.selectRawSku('raw', 'SKU-RAW');
    expect(vm.publishBindingErrors.map((error) => error.nodeId)).toEqual(['finished']);
  });

  it('hydrates only B when B resolves before the earlier A request', async () => {
    const a = deferred<ApiResponse>();
    const b = deferred<ApiResponse>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => (productTypeId === 'PT-A' ? a.promise : b.promise),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    b.resolve({ success: true, data: definitionFor('PT-B') });
    await flushPromises();
    expect(editorVm(wrapper).flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);

    a.resolve({ success: true, data: definitionFor('PT-A') });
    await flushPromises();
    expect(editorVm(wrapper).flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);
  });

  it('keeps B loading when the stale A request finalizes first', async () => {
    const a = deferred<ApiResponse>();
    const b = deferred<ApiResponse>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => (productTypeId === 'PT-A' ? a.promise : b.promise),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    a.resolve({ success: true, data: definitionFor('PT-A') });
    await flushPromises();

    expect(editorVm(wrapper).loading).toBe(true);
    expect(editorVm(wrapper).canEdit).toBe(false);
    expect(editorVm(wrapper).flowNodes).toEqual([]);

    b.resolve({ success: true, data: definitionFor('PT-B') });
    await flushPromises();
    expect(editorVm(wrapper).loading).toBe(false);
    expect(editorVm(wrapper).flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);
  });

  it('clears and locks A immediately when B starts, and keeps B locked after failure', async () => {
    const b = deferred<ApiResponse>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => (
        productTypeId === 'PT-A'
          ? Promise.resolve({ success: true, data: definitionFor('PT-A') })
          : b.promise
      ),
    );
    const wrapper = mountEditor();
    await flushPromises();
    expect(editorVm(wrapper).flowNodes.map((node) => node.id)).toEqual(['node:PT-A']);

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await nextTick();
    expect(editorVm(wrapper).flowNodes).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);
    editorVm(wrapper).addStandaloneRaw();
    expect(editorVm(wrapper).flowNodes).toEqual([]);

    b.reject(new Error('B load failed'));
    await flushPromises();
    await editorVm(wrapper).saveDraft();
    await editorVm(wrapper).publishWorkflow();

    expect(editorVm(wrapper).flowNodes).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);
    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();
  });

  it('ignores A when it resolves after B has already failed', async () => {
    const a = deferred<ApiResponse>();
    const b = deferred<ApiResponse>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => (productTypeId === 'PT-A' ? a.promise : b.promise),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    b.reject(new Error('B load failed'));
    await flushPromises();
    a.resolve({ success: true, data: definitionFor('PT-A') });
    await flushPromises();

    expect(editorVm(wrapper).flowNodes).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);
  });

  it('does not save or publish when the loaded factory identity no longer matches props', async () => {
    const nextFactoryLoad = deferred<ApiResponse>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (factoryId: string) => (
        factoryId === 'F006'
          ? Promise.resolve({ success: true, data: definitionFor('PT-A') })
          : nextFactoryLoad.promise
      ),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ factoryId: 'F007' });
    await editorVm(wrapper).saveDraft();
    await editorVm(wrapper).publishWorkflow();

    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();
  });

  it('keeps F007 locked and empty until its definition and catalogs both load', async () => {
    const f007Catalog = deferred<TestCatalog>();
    installCatalogMocks({
      F006: Promise.resolve(catalogFor('F006')),
      F007: f007Catalog.promise,
    });
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: { ...definitionFor(productTypeId), factoryId },
      }),
    );
    const wrapper = mountEditor();
    await flushPromises();
    expect(editorVm(wrapper).workProcessOptions.map((item) => item.id)).toEqual(['WP-F006']);

    await wrapper.setProps({ factoryId: 'F007' });
    await flushPromises();
    expect(editorVm(wrapper).flowNodes).toHaveLength(1);
    expect(editorVm(wrapper).workProcessOptions).toEqual([]);
    expect(editorVm(wrapper).skuOptions).toEqual([]);
    expect(editorVm(wrapper).rawMaterialOptions).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);

    f007Catalog.resolve(catalogFor('F007'));
    await flushPromises();
    expect(editorVm(wrapper).workProcessOptions.map((item) => item.id)).toEqual(['WP-F007']);
    expect(editorVm(wrapper).skuOptions.map((item) => item.id)).toEqual(['SKU-F007']);
    expect(editorVm(wrapper).rawMaterialOptions.map((item) => item.id)).toEqual(['RAW-F007']);
    expect(editorVm(wrapper).canEdit).toBe(true);
  });

  it('keeps F007 catalogs empty and editing locked when its catalog load fails', async () => {
    const f007Catalog = deferred<TestCatalog>();
    void f007Catalog.promise.catch(() => undefined);
    installCatalogMocks({
      F006: Promise.resolve(catalogFor('F006')),
      F007: f007Catalog.promise,
    });
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: { ...definitionFor(productTypeId), factoryId },
      }),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ factoryId: 'F007' });
    f007Catalog.reject(new Error('F007 catalog failed'));
    await flushPromises();

    expect(editorVm(wrapper).workProcessOptions).toEqual([]);
    expect(editorVm(wrapper).skuOptions).toEqual([]);
    expect(editorVm(wrapper).rawMaterialOptions).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);
  });

  it('ignores late F006 catalogs after F007 catalogs have loaded', async () => {
    const f006Catalog = deferred<TestCatalog>();
    const f007Catalog = deferred<TestCatalog>();
    installCatalogMocks({ F006: f006Catalog.promise, F007: f007Catalog.promise });
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: { ...definitionFor(productTypeId), factoryId },
      }),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ factoryId: 'F007' });
    f007Catalog.resolve(catalogFor('F007'));
    await flushPromises();
    expect(editorVm(wrapper).canEdit).toBe(true);

    f006Catalog.resolve(catalogFor('F006'));
    await flushPromises();

    expect(editorVm(wrapper).workProcessOptions.map((item) => item.id)).toEqual(['WP-F007']);
    expect(editorVm(wrapper).skuOptions.map((item) => item.id)).toEqual(['SKU-F007']);
    expect(editorVm(wrapper).rawMaterialOptions.map((item) => item.id)).toEqual(['RAW-F007']);
    expect(editorVm(wrapper).canEdit).toBe(true);
  });

  it('ignores an A create-SKU success after B has loaded and opened its own dialog', async () => {
    const aCreate = deferred<{ success: boolean; data: TestSku }>();
    apiMocks.post.mockReturnValue(aCreate.promise);
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definitionWithOutput(factoryId, productTypeId),
      }),
    );
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined);
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined);
    const wrapper = mountEditor();
    await flushPromises();
    editorVm(wrapper).selectOutputSku('process:shared', 'output:shared', '__CREATE__');
    const aCreatePromise = editorVm(wrapper).confirmCreateSku();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    editorVm(wrapper).selectOutputSku('process:shared', 'output:shared', '__CREATE__');
    expect(editorVm(wrapper).skuDialogVisible).toBe(true);

    aCreate.resolve({ success: true, data: testSku('SKU-A-CREATED') });
    await aCreatePromise;

    expect(editorVm(wrapper).skuOptions.some((item) => item.id === 'SKU-A-CREATED')).toBe(false);
    expect(editorVm(wrapper).flowNodes.find((node) => node.id === 'material:shared')?.data).toMatchObject({
      skuId: '',
      bound: false,
    });
    expect(editorVm(wrapper).skuDialogVisible).toBe(true);
    expect(successSpy).not.toHaveBeenCalled();
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('ignores an A create-SKU rejection and finalizer while B create is pending', async () => {
    const aCreate = deferred<{ success: boolean; data: TestSku }>();
    const bCreate = deferred<{ success: boolean; data: TestSku }>();
    void aCreate.promise.catch(() => undefined);
    apiMocks.post
      .mockReturnValueOnce(aCreate.promise)
      .mockReturnValueOnce(bCreate.promise);
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definitionWithOutput(factoryId, productTypeId),
      }),
    );
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined);
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined);
    const wrapper = mountEditor();
    await flushPromises();
    editorVm(wrapper).selectOutputSku('process:shared', 'output:shared', '__CREATE__');
    const aCreatePromise = editorVm(wrapper).confirmCreateSku();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    editorVm(wrapper).selectOutputSku('process:shared', 'output:shared', '__CREATE__');
    const bCreatePromise = editorVm(wrapper).confirmCreateSku();
    await flushPromises();
    expect(editorVm(wrapper).creatingSku).toBe(true);

    aCreate.reject(new Error('stale A rejection'));
    await aCreatePromise;

    expect(editorVm(wrapper).creatingSku).toBe(true);
    expect(editorVm(wrapper).skuDialogVisible).toBe(true);
    expect(successSpy).not.toHaveBeenCalled();
    expect(errorSpy).not.toHaveBeenCalled();

    bCreate.resolve({ success: true, data: testSku('SKU-B-CREATED') });
    await bCreatePromise;
  });

  it('does not apply an A graph edit confirmed after switching to B', async () => {
    const b = deferred<ApiResponse>();
    const confirmation = deferred<'confirm'>();
    vi.mocked(ElMessageBox.confirm).mockReturnValue(confirmation.promise);
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => (
        productTypeId === 'PT-A'
          ? Promise.resolve({ success: true, data: definitionFor('PT-A') })
          : b.promise
      ),
    );
    const wrapper = mountEditor();
    await flushPromises();

    const applyPromise = editorVm(wrapper).applyWorkflowAIDraft({
      patches: [{
        op: 'SET_NODE_FIELD',
        nodeId: 'node:PT-A',
        path: 'name',
        value: 'AI changed A',
      }],
    }, { factoryId: 'F006', productTypeId: 'PT-A' });
    await flushPromises();
    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    confirmation.resolve('confirm');
    await applyPromise;

    expect(editorVm(wrapper).flowNodes).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);
  });

  it('rejects an A diff emitted only after B has finished loading', async () => {
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definitionFor(productTypeId, 'material:raw'),
      }),
    );
    const wrapper = mountEditor();
    await flushPromises();
    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    expect(editorVm(wrapper).canEdit).toBe(true);

    await editorVm(wrapper).applyWorkflowAIDraft({
      patches: [{
        op: 'SET_NODE_FIELD',
        nodeId: 'material:raw',
        path: 'name',
        value: 'stale A name',
      }],
    }, { factoryId: 'F006', productTypeId: 'PT-A' });

    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(editorVm(wrapper).flowNodes[0]).toMatchObject({
      id: 'material:raw',
      data: { name: 'PT-B raw' },
    });
  });

  it('hydrates a normal load and allows graph editing', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    const wrapper = mountEditor();
    await flushPromises();

    expect(editorVm(wrapper).flowNodes.map((node) => node.id)).toEqual(['node:PT-A']);
    expect(editorVm(wrapper).canEdit).toBe(true);
    editorVm(wrapper).addStandaloneRaw();
    expect(editorVm(wrapper).flowNodes).toHaveLength(2);
  });

  it('keeps the local draft intact until an explicit conflict reload hydrates the latest version', async () => {
    const recoveryChoice = deferred<'confirm'>();
    const latest = {
      ...definitionFor('PT-A', 'node:latest'),
      lockVersion: 2,
    };
    apiMocks.getProductProcessWorkflow
      .mockResolvedValueOnce({ success: true, data: definitionFor('PT-A') })
      .mockResolvedValueOnce({ success: true, data: latest });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
      message: 'Workflow was updated by another user',
    });
    vi.mocked(ElMessageBox.confirm).mockReturnValue(recoveryChoice.promise);
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 120, y: 40, zoom: 0.8 });
    vm.history.push(jsonClone(vm.currentDefinition()));
    const nodesBeforeConflict = jsonClone(vm.flowNodes);
    const historyBeforeConflict = jsonClone(vm.history);

    const savePromise = vm.saveDraft();
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    expect(vm.flowNodes).toEqual(nodesBeforeConflict);
    expect(vm.history).toEqual(historyBeforeConflict);
    expect(vm.dirty).toBe(true);
    expect(apiMocks.getProductProcessWorkflow).toHaveBeenCalledTimes(1);

    recoveryChoice.resolve('confirm');
    await savePromise;
    await flushPromises();

    expect(apiMocks.getProductProcessWorkflow).toHaveBeenCalledTimes(2);
    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:latest']);
    expect(vm.dirty).toBe(false);
  });

  it('copies the exact local draft for a semantic JPA optimistic-lock 409 without retrying the mutation', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      response: {
        status: 409,
        data: {
          errorCode: 'OPTIMISTIC_LOCK_CONFLICT',
          message: 'Optimistic lock conflict',
          actionHint: 'Refresh latest data',
        },
      },
    });
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel');
    const writeText = installClipboardMock();
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 64, y: 96, zoom: 0.75 });

    await vm.saveDraft();

    const submitted = apiMocks.saveProductProcessWorkflowDraft.mock.calls[0][2];
    expect(writeText).toHaveBeenCalledWith(JSON.stringify(submitted, null, 2));
    expect(apiMocks.saveProductProcessWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();
    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:PT-A']);
    expect(vm.dirty).toBe(true);
  });

  it('copies live edits made after the conflicted mutation started', async () => {
    const recoveryChoice = deferred<'confirm'>();
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    });
    vi.mocked(ElMessageBox.confirm).mockReturnValue(recoveryChoice.promise);
    const writeText = installClipboardMock();
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 64, y: 96, zoom: 0.75 });

    const savePromise = vm.saveDraft();
    await flushPromises();
    vm.flowNodes[0].data.name = 'Edited after the request started';
    recoveryChoice.reject('cancel');
    await savePromise;

    const copied = JSON.parse(String(writeText.mock.calls[0][0])) as ProductProcessWorkflowDefinition;
    expect(copied.nodes[0].data.name).toBe('Edited after the request started');
    expect(apiMocks.saveProductProcessWorkflowDraft).toHaveBeenCalledTimes(1);
    expect(vm.flowNodes[0].data.name).toBe('Edited after the request started');
    expect(vm.dirty).toBe(true);
  });

  it('keeps the local draft when the conflict recovery dialog is closed', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    });
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('close');
    const writeText = installClipboardMock();
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 16, y: 32, zoom: 0.9 });
    const localSnapshot = vm.currentDefinition();

    await vm.saveDraft();

    expect(vm.currentDefinition()).toEqual(localSnapshot);
    expect(vm.dirty).toBe(true);
    expect(writeText).not.toHaveBeenCalled();
    expect(apiMocks.getProductProcessWorkflow).toHaveBeenCalledTimes(1);
  });

  it('retains the existing non-409 failure path without opening conflict recovery', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({ status: 500 });
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 32, y: 16, zoom: 1 });
    const localSnapshot = vm.currentDefinition();

    await vm.saveDraft();

    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(vm.currentDefinition()).toEqual(localSnapshot);
    expect(vm.dirty).toBe(true);
  });

  it('does not open workflow recovery for an unrelated rich 409', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'UNRELATED_RICH_CONFLICT',
      actionHint: 'Resolve the unrelated business rule',
    });
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 32, y: 16, zoom: 1 });

    await vm.saveDraft();

    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(vm.dirty).toBe(true);
  });

  it('opens workflow recovery for the semantic generic JPA optimistic-lock code', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'OPTIMISTIC_LOCK_CONFLICT',
      actionHint: 'Refresh latest data',
    });
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('close');
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 32, y: 16, zoom: 1 });

    await vm.saveDraft();

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    expect(vm.dirty).toBe(true);
  });

  it('does not use a bare 409 status as workflow conflict semantics', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definitionFor('PT-A'),
    });
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({ status: 409 });
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 32, y: 16, zoom: 1 });

    await vm.saveDraft();

    expect(ElMessageBox.confirm).not.toHaveBeenCalled();
    expect(vm.dirty).toBe(true);
  });

  it('does not reload a conflicted product after the user has switched products', async () => {
    const recoveryChoice = deferred<'confirm'>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definitionFor(productTypeId),
      }),
    );
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    });
    vi.mocked(ElMessageBox.confirm).mockReturnValue(recoveryChoice.promise);
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 48, y: 48, zoom: 0.8 });
    const savePromise = vm.saveDraft();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);

    recoveryChoice.resolve('confirm');
    await savePromise;
    await flushPromises();

    expect(apiMocks.getProductProcessWorkflow).toHaveBeenCalledTimes(2);
    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);
  });

  it('does not copy a newly selected product from an old conflict dialog', async () => {
    const recoveryChoice = deferred<'confirm'>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definitionFor(productTypeId),
      }),
    );
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    });
    vi.mocked(ElMessageBox.confirm).mockReturnValue(recoveryChoice.promise);
    const writeText = installClipboardMock();
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 48, y: 48, zoom: 0.8 });
    const savePromise = vm.saveDraft();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    recoveryChoice.reject('cancel');
    await savePromise;

    expect(writeText).not.toHaveBeenCalled();
    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);
  });

  it('ignores a conflicted reload response that arrives after switching products', async () => {
    const staleReload = deferred<ApiResponse>();
    let aLoads = 0;
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => {
        if (productTypeId === 'PT-A') {
          aLoads += 1;
          return aLoads === 1
            ? Promise.resolve({ success: true, data: definitionFor('PT-A') })
            : staleReload.promise;
        }
        return Promise.resolve({ success: true, data: definitionFor('PT-B') });
      },
    );
    apiMocks.saveProductProcessWorkflowDraft.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    });
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm');
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    vm.onViewportChangeEnd({ x: 48, y: 48, zoom: 0.8 });

    const savePromise = vm.saveDraft();
    await flushPromises();
    expect(apiMocks.getProductProcessWorkflow).toHaveBeenCalledTimes(2);

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);

    staleReload.resolve({ success: true, data: definitionFor('PT-A', 'node:stale-reload') });
    await savePromise;
    await flushPromises();

    expect(vm.flowNodes.map((node) => node.id)).toEqual(['node:PT-B']);
  });

  it('offers the same safe copy recovery when publish conflicts', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: publishableDefinition('PT-A'),
    });
    apiMocks.publishProductProcessWorkflow.mockRejectedValue({
      status: 409,
      code: 'PRODUCT_PROCESS_WORKFLOW_CONFLICT',
    });
    vi.mocked(ElMessageBox.confirm)
      .mockResolvedValueOnce('confirm')
      .mockRejectedValueOnce('cancel');
    const writeText = installClipboardMock();
    const wrapper = mountEditor();
    await flushPromises();
    const vm = editorVm(wrapper);
    const localSnapshot = vm.currentDefinition();

    await vm.publishWorkflow();

    expect(apiMocks.publishProductProcessWorkflow).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(JSON.stringify(localSnapshot, null, 2));
    expect(vm.currentDefinition()).toEqual(localSnapshot);
    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
  });
});

function mountEditor(): VueWrapper {
  return shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      productName: 'Product A',
      canWrite: true,
    },
  });
}

function editorVm(wrapper: VueWrapper): EditorVm {
  return wrapper.vm as unknown as EditorVm;
}

function definitionFor(productTypeId: string, nodeId = `node:${productTypeId}`): ProductProcessWorkflowDefinition {
  return {
    id: productTypeId === 'PT-A' ? 1 : 2,
    factoryId: 'F006',
    productTypeId,
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    lockVersion: 0,
    nodes: [{
      id: nodeId,
      kind: 'RAW_MATERIAL',
      position: { x: 16, y: 32 },
      data: { name: `${productTypeId} raw`, skuId: `SKU:${productTypeId}`, baseUnit: 'kg' },
    }],
    edges: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function definitionWithOutput(factoryId: string, productTypeId: string): ProductProcessWorkflowDefinition {
  return {
    id: productTypeId === 'PT-A' ? 1 : 2,
    factoryId,
    productTypeId,
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    lockVersion: 0,
    nodes: [{
      id: 'process:shared',
      kind: 'PROCESS',
      position: { x: 256, y: 32 },
      data: {
        workProcessId: `WP-${factoryId}`,
        processName: 'Shared process',
        inputUnit: 'kg',
        outputUnit: 'kg',
        ports: [{
          id: 'output:shared',
          direction: 'OUTPUT',
          materialNodeId: 'material:shared',
          materialKind: 'SEMI_FINISHED',
          unit: 'kg',
          ordinal: 0,
        }],
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        reportingRequired: true,
      },
    }, {
      id: 'material:shared',
      kind: 'SEMI_FINISHED',
      position: { x: 736, y: 32 },
      data: { name: `${productTypeId} output`, skuId: '', baseUnit: 'kg', bound: false },
    }],
    edges: [{
      id: 'edge:shared',
      source: 'process:shared',
      sourceHandle: 'output:shared',
      target: 'material:shared',
      targetHandle: 'input',
    }],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function publishableDefinition(productTypeId: string): ProductProcessWorkflowDefinition {
  return {
    id: 1,
    factoryId: 'F006',
    productTypeId,
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    lockVersion: 0,
    nodes: [{
      id: 'raw',
      kind: 'RAW_MATERIAL',
      position: { x: 16, y: 32 },
      data: { name: 'Raw', skuId: 'SKU-RAW', baseUnit: 'kg', bound: true },
    }, {
      id: 'process',
      kind: 'PROCESS',
      position: { x: 320, y: 32 },
      data: {
        workProcessId: 'WP-1',
        processName: 'Pack',
        inputUnit: 'kg',
        outputUnit: 'kg',
        ports: [{
          id: 'in', direction: 'INPUT', materialNodeId: 'raw', materialKind: 'RAW_MATERIAL', unit: 'kg', ordinal: 0,
        }, {
          id: 'out', direction: 'OUTPUT', materialNodeId: 'finished', materialKind: 'FINISHED_GOOD', unit: 'kg', ordinal: 0,
        }],
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        reportingRequired: true,
      },
    }, {
      id: 'finished',
      kind: 'FINISHED_GOOD',
      position: { x: 736, y: 32 },
      data: { name: 'Finished', skuId: productTypeId, baseUnit: 'kg', bound: true },
    }],
    edges: [{ id: 'raw-process', source: 'raw', sourceHandle: 'output', target: 'process', targetHandle: 'in' }, {
      id: 'process-finished', source: 'process', sourceHandle: 'out', target: 'finished', targetHandle: 'input',
    }],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function installClipboardMock(): ReturnType<typeof vi.fn> {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  });
  return writeText;
}

function jsonClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

interface TestSku {
  id: string;
  name: string;
  unit: string;
  productCategory: string;
}

function testSku(id: string): TestSku {
  return { id, name: id, unit: 'kg', productCategory: 'SEMI_FINISHED' };
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

interface TestCatalog {
  processes: Array<{ id: string }>;
  rawMaterials: Array<{ id: string; name: string; category: string }>;
  materialSegments: Array<{
    level: number;
    segmentCode: string;
    segmentLabel: string;
    children: Array<{ level: number; segmentCode: string; segmentLabel: string }>;
  }>;
  skus: Array<{ id: string; name: string; unit: string; productCategory: string }>;
}

function catalogFor(factoryId: string): TestCatalog {
  return {
    processes: [{ id: `WP-${factoryId}` }],
    rawMaterials: [{ id: `RAW-${factoryId}`, name: `Raw ${factoryId}`, category: '主材' }],
    materialSegments: [{
      level: 1,
      segmentCode: '001',
      segmentLabel: '原料',
      children: [{ level: 3, segmentCode: '0010010001', segmentLabel: `Raw ${factoryId}` }],
    }],
    skus: [{
      id: `SKU-${factoryId}`,
      name: `SKU ${factoryId}`,
      unit: 'kg',
      productCategory: 'SEMI_FINISHED',
    }],
  };
}

function installCatalogMocks(catalogs: Record<string, Promise<TestCatalog>>): void {
  apiMocks.getActiveWorkProcesses.mockImplementation((factoryId: string) => (
    catalogs[factoryId].then((catalog) => ({ success: true, data: catalog.processes }))
  ));
  apiMocks.get.mockImplementation((url: string) => {
    const factoryId = url.split('/')[1];
    return catalogs[factoryId].then((catalog) => (
      url.includes('/product-types')
        ? { success: true, data: { content: catalog.skus } }
        : url.includes('/material-segments/tree')
          ? { success: true, data: catalog.materialSegments }
          : { success: true, data: catalog.rawMaterials }
    ));
  });
}

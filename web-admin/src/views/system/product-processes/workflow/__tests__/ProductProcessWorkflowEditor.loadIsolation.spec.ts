import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessage, ElMessageBox } from 'element-plus';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import type { ProductProcessWorkflowDefinition } from '../types';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
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
  publishProductProcessWorkflow: apiMocks.publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft: apiMocks.saveProductProcessWorkflowDraft,
}));

vi.mock('@vue-flow/core', () => ({
  MarkerType: { ArrowClosed: 'arrow-closed' },
  VueFlow: defineComponent({ name: 'VueFlow', template: '<div />' }),
  useVueFlow: () => ({
    fitView: vi.fn(),
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
  flowNodes: Array<{ id: string; data: Record<string, unknown> }>;
  loading: boolean;
  rawMaterialOptions: Array<{ id: string }>;
  skuOptions: Array<{ id: string }>;
  workProcessOptions: Array<{ id: string }>;
  addStandaloneRaw: () => void;
  applyWorkflowAIDraft: (
    payload: Record<string, unknown>,
    sourceIdentity?: { factoryId: string; productTypeId: string },
  ) => Promise<void>;
  publishWorkflow: () => Promise<void>;
  saveDraft: () => Promise<boolean>;
  confirmCreateSku: () => Promise<void>;
  selectOutputSku: (processId: string, portId: string, skuId: string) => void;
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
    apiMocks.get.mockImplementation((url: string) => Promise.resolve(
      url.includes('/product-types')
        ? { success: true, data: { content: [] } }
        : { success: true, data: [] },
    ));
    apiMocks.getActiveWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
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
  rawMaterials: Array<{ id: string; name: string }>;
  skus: Array<{ id: string; name: string; unit: string; productCategory: string }>;
}

function catalogFor(factoryId: string): TestCatalog {
  return {
    processes: [{ id: `WP-${factoryId}` }],
    rawMaterials: [{ id: `RAW-${factoryId}`, name: `Raw ${factoryId}` }],
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
        : { success: true, data: catalog.rawMaterials }
    ));
  });
}

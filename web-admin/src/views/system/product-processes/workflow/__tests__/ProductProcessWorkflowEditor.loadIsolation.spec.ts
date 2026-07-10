import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ElMessageBox } from 'element-plus';
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
  flowNodes: Array<{ id: string }>;
  loading: boolean;
  addStandaloneRaw: () => void;
  applyWorkflowAIDraft: (payload: Record<string, unknown>) => Promise<void>;
  publishWorkflow: () => Promise<void>;
  saveDraft: () => Promise<boolean>;
}

interface ApiResponse {
  success: boolean;
  data: ProductProcessWorkflowDefinition | null;
}

describe('ProductProcessWorkflowEditor load identity isolation', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    apiMocks.get.mockResolvedValue({ success: true, data: [] });
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
    });
    await flushPromises();
    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    confirmation.resolve('confirm');
    await applyPromise;

    expect(editorVm(wrapper).flowNodes).toEqual([]);
    expect(editorVm(wrapper).canEdit).toBe(false);
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

function definitionFor(productTypeId: string): ProductProcessWorkflowDefinition {
  return {
    id: productTypeId === 'PT-A' ? 1 : 2,
    factoryId: 'F006',
    productTypeId,
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    lockVersion: 0,
    nodes: [{
      id: `node:${productTypeId}`,
      kind: 'RAW_MATERIAL',
      position: { x: 16, y: 32 },
      data: { name: `${productTypeId} raw`, skuId: `SKU:${productTypeId}`, baseUnit: 'kg' },
    }],
    edges: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
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

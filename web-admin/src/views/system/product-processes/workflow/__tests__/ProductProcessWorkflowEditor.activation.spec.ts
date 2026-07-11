import { flushPromises, shallowMount } from '@vue/test-utils';
import { ElMessageBox } from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProductProcessWorkflowEditor from '../ProductProcessWorkflowEditor.vue';
import type {
  ProductProcessWorkflowActivation,
  ProductProcessWorkflowDefinition,
} from '../types';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
  activateProductProcessWorkflow: vi.fn(),
  deactivateProductProcessWorkflow: vi.fn(),
}));

vi.mock('@/api/request', () => ({ get: apiMocks.get, post: apiMocks.post }));
vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: apiMocks.getActiveWorkProcesses,
  getProductWorkProcesses: apiMocks.getProductWorkProcesses,
}));
vi.mock('../workflowApi', () => ({
  getProductProcessWorkflow: apiMocks.getProductProcessWorkflow,
  getProductProcessWorkflowActivation: apiMocks.getProductProcessWorkflowActivation,
  publishProductProcessWorkflow: apiMocks.publishProductProcessWorkflow,
  saveProductProcessWorkflowDraft: apiMocks.saveProductProcessWorkflowDraft,
  activateProductProcessWorkflow: apiMocks.activateProductProcessWorkflow,
  deactivateProductProcessWorkflow: apiMocks.deactivateProductProcessWorkflow,
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

describe('ProductProcessWorkflowEditor activation controls', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    apiMocks.get.mockImplementation((url: string) => Promise.resolve({
      success: true,
      data: url.includes('/product-types') ? { content: [] } : [],
    }));
    apiMocks.post.mockResolvedValue({ success: true, data: null });
    apiMocks.getActiveWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getProductWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definition('PT-A', 'DRAFT', 1, 43),
    });
    apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({ success: true, data: null });
    apiMocks.publishProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definition('PT-A', 'PUBLISHED', 2, 44),
    });
    apiMocks.activateProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: activation('PT-A', 44, 2),
    });
  });

  it('publishing never activates and activation requires its own future-batches confirmation', async () => {
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.get('[data-testid="publish-workflow"]').trigger('click');
    await flushPromises();

    expect(apiMocks.publishProductProcessWorkflow).toHaveBeenCalledTimes(1);
    expect(apiMocks.activateProductProcessWorkflow).not.toHaveBeenCalled();
    expect(wrapper.get('[data-testid="activate-workflow"]').text()).toContain('启用版本 v2');

    await wrapper.get('[data-testid="activate-workflow"]').trigger('click');
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenLastCalledWith(
      expect.stringContaining('只影响之后新建的生产批次；正在生产的批次不会变化。'),
      expect.any(String),
      expect.any(Object),
    );
    expect(apiMocks.activateProductProcessWorkflow).toHaveBeenCalledWith('F006', 44);
  });

  it('treats no activation as normal and ignores a stale activation response after product switch', async () => {
    const staleA = deferred<{ success: true; data: ProductProcessWorkflowActivation | null }>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definition(productTypeId, 'PUBLISHED', productTypeId === 'PT-A' ? 3 : 4,
          productTypeId === 'PT-A' ? 43 : 44),
      }),
    );
    apiMocks.getProductProcessWorkflowActivation.mockImplementation(
      (_factoryId: string, productTypeId: string) => productTypeId === 'PT-A'
        ? staleA.promise
        : Promise.resolve({ success: true, data: activation('PT-B', 44, 4) }),
    );
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    staleA.resolve({ success: true, data: activation('PT-A', 43, 3) });
    await flushPromises();

    expect(wrapper.get('[data-testid="activation-status"]').text()).toContain('已启用 v4');
    expect(wrapper.get('[data-testid="activation-status"]').text()).not.toContain('v3');
  });

  it('clears mutation loading when switching products before the old activation finishes', async () => {
    const staleActivation = deferred<{
      success: true;
      data: ProductProcessWorkflowActivation;
    }>();
    apiMocks.getProductProcessWorkflow.mockImplementation(
      (_factoryId: string, productTypeId: string) => Promise.resolve({
        success: true,
        data: definition(productTypeId, 'PUBLISHED', productTypeId === 'PT-A' ? 3 : 4,
          productTypeId === 'PT-A' ? 43 : 44),
      }),
    );
    apiMocks.activateProductProcessWorkflow.mockReturnValueOnce(staleActivation.promise);
    const wrapper = mountEditor();
    await flushPromises();

    await wrapper.get('[data-testid="activate-workflow"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="activate-workflow"]').attributes('loading')).toBe('true');

    await wrapper.setProps({ productTypeId: 'PT-B', productName: 'Product B' });
    await flushPromises();
    staleActivation.resolve({ success: true, data: activation('PT-A', 43, 3) });
    await flushPromises();

    expect(wrapper.get('[data-testid="activate-workflow"]').text()).toContain('启用版本 v4');
    expect(wrapper.get('[data-testid="activate-workflow"]').attributes('loading')).toBe('false');
  });
});

function mountEditor() {
  return shallowMount(ProductProcessWorkflowEditor, {
    props: {
      factoryId: 'F006',
      productTypeId: 'PT-A',
      productName: 'Product A',
      canWrite: true,
    },
  });
}

function definition(
  productTypeId: string,
  status: 'DRAFT' | 'PUBLISHED',
  version: number,
  id: number,
): ProductProcessWorkflowDefinition {
  return {
    id,
    factoryId: 'F006',
    productTypeId,
    schemaVersion: 1,
    status,
    version,
    lockVersion: 0,
    nodes: [{
      id: 'raw',
      kind: 'RAW_MATERIAL',
      position: { x: 16, y: 16 },
      data: { name: 'Raw', skuId: 'RAW', baseUnit: 'kg', bound: true },
    }, {
      id: 'process',
      kind: 'PROCESS',
      position: { x: 320, y: 16 },
      data: {
        workProcessId: 'CUT',
        processName: 'Cut',
        inputUnit: 'kg',
        outputUnit: 'kg',
        ports: [
          { id: 'in', direction: 'INPUT', materialNodeId: 'raw', materialKind: 'RAW_MATERIAL', unit: 'kg', ordinal: 0 },
          { id: 'out', direction: 'OUTPUT', materialNodeId: 'finished', materialKind: 'FINISHED_GOOD', unit: 'kg', ordinal: 0 },
        ],
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        reportingRequired: true,
      },
    }, {
      id: 'finished',
      kind: 'FINISHED_GOOD',
      position: { x: 640, y: 16 },
      data: { name: 'Finished', skuId: productTypeId, baseUnit: 'kg', bound: true },
    }],
    edges: [
      { id: 'e1', source: 'raw', sourceHandle: 'output', target: 'process', targetHandle: 'in' },
      { id: 'e2', source: 'process', sourceHandle: 'out', target: 'finished', targetHandle: 'input' },
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function activation(
  productTypeId: string,
  activeWorkflowId: number,
  activeDefinitionVersion: number,
): ProductProcessWorkflowActivation {
  return {
    id: 91,
    factoryId: 'F006',
    productTypeId,
    activeWorkflowId,
    activeDefinitionVersion,
    enabled: true,
    activatedBy: 7001,
    activatedAt: '2026-07-11T12:00:00',
    lockVersion: 1,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

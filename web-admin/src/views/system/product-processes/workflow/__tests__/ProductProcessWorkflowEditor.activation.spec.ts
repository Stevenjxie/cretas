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
  createWorkProcess: vi.fn(),
  getActiveWorkProcesses: vi.fn(),
  getWorkProcessCategories: vi.fn(),
  getProductWorkProcesses: vi.fn(),
  updateWorkProcess: vi.fn(),
  updateWorkProcessOutputKind: vi.fn(),
  getProductProcessWorkflow: vi.fn(),
  getProductProcessWorkflowActivation: vi.fn(),
  publishProductProcessWorkflow: vi.fn(),
  saveProductProcessWorkflowDraft: vi.fn(),
  snapshotProductProcessWorkflow: vi.fn(),
  activateProductProcessWorkflow: vi.fn(),
  deactivateProductProcessWorkflow: vi.fn(),
}));

vi.mock('@/api/request', () => ({ get: apiMocks.get, post: apiMocks.post }));
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
  activateProductProcessWorkflow: apiMocks.activateProductProcessWorkflow,
  deactivateProductProcessWorkflow: apiMocks.deactivateProductProcessWorkflow,
  listProductProcessWorkflowVersions: vi.fn().mockResolvedValue({ success: true, data: [] }),
  getProductProcessWorkflowVersion: vi.fn().mockResolvedValue({ success: true, data: null }),
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

describe('ProductProcessWorkflowEditor activation controls', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    apiMocks.get.mockImplementation((url: string) => Promise.resolve({
      success: true,
      data: url.includes('/bom/recipes/by-product/')
        ? { id: 'R-1', items: [{ id: 1, materialTypeId: 'RAW', materialName: 'Raw', unit: 'kg' }] }
        : url.endsWith('/product-types/PT-A')
          ? productOption('PT-A')
        : url.includes('/product-types')
        ? { content: [productOption('PT-A'), productOption('PT-B')] }
        : url.includes('/raw-material-types')
          ? [{ id: 'RAW', name: 'Raw', unit: 'kg', category: '原料' }]
          : [],
    }));
    apiMocks.post.mockResolvedValue({ success: true, data: null });
    apiMocks.getActiveWorkProcesses.mockResolvedValue({ success: true, data: [] });
    apiMocks.getWorkProcessCategories.mockResolvedValue({ success: true, data: [] });
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

  it('#12a: 发布即启用当前版本 (一步), 不再有单独的「启用版本」按钮', async () => {
    const wrapper = mountEditor();
    await flushPromises();

    await (wrapper.vm as unknown as { publishWorkflow: () => Promise<void> }).publishWorkflow();
    await flushPromises();

    expect((wrapper.vm as unknown as { unitIssues: unknown[] }).unitIssues).toEqual([]);
    expect((wrapper.vm as unknown as { publishBindingErrors: unknown[] }).publishBindingErrors).toEqual([]);
    expect((wrapper.vm as unknown as { bomMissingProducts: unknown[] }).bomMissingProducts).toEqual([]);
    expect(apiMocks.publishProductProcessWorkflow).toHaveBeenCalledTimes(1);
    // 发布成功后自动用发布出的版本 (id 44) 启用, 无需用户再点一次
    expect(apiMocks.activateProductProcessWorkflow).toHaveBeenCalledWith('F006', 44);
    // 单独的「启用版本」按钮已移除
    expect(wrapper.find('[data-testid="activate-workflow"]').exists()).toBe(false);
    // 版本记录浏览按钮存在 (#12b)
    expect(wrapper.find('[data-testid="browse-versions"]').exists()).toBe(true);
  });

  it('disables republishing when the currently displayed version is already enabled and clean', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definition('PT-A', 'PUBLISHED', 2, 44),
    });
    apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({
      success: true,
      data: activation('PT-A', 44, 2),
    });

    const wrapper = mountEditor();
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      publishDisabledReason: string;
      publishWorkflow: () => Promise<void>;
    };

    expect(vm.publishDisabledReason).toContain('v2 已发布并启用');
    expect(wrapper.get('[data-testid="publish-workflow"]').attributes('disabled')).toBeDefined();

    await vm.publishWorkflow();
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();
  });

  it('keeps publish available for a saved v2 draft while v1 is enabled', async () => {
    apiMocks.getProductProcessWorkflow.mockResolvedValue({
      success: true,
      data: definition('PT-A', 'DRAFT', 2, 45),
    });
    apiMocks.getProductProcessWorkflowActivation.mockResolvedValue({
      success: true,
      data: activation('PT-A', 44, 1),
    });

    const wrapper = mountEditor();
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      publishDisabledReason: string;
      publishWorkflow: () => Promise<void>;
    };

    expect(vm.publishDisabledReason).toBe('');
    await vm.publishWorkflow();
    await flushPromises();
    expect(apiMocks.publishProductProcessWorkflow).toHaveBeenCalledTimes(1);
  });

  it('prevents a second publish while the confirmation dialog is pending', async () => {
    const confirmation = deferred<'confirm'>();
    vi.spyOn(ElMessageBox, 'confirm').mockReturnValueOnce(confirmation.promise as never);
    const wrapper = mountEditor();
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      publishDisabledReason: string;
      publishWorkflow: () => Promise<void>;
    };

    const firstPublish = vm.publishWorkflow();
    await flushPromises();
    expect(vm.publishDisabledReason).toContain('正在发布 Workflow');
    await vm.publishWorkflow();
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    expect(apiMocks.publishProductProcessWorkflow).not.toHaveBeenCalled();

    confirmation.resolve('confirm');
    await firstPublish;
    await flushPromises();
    expect(apiMocks.publishProductProcessWorkflow).toHaveBeenCalledTimes(1);
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

  it('keeps published workflow read-only when a broad unit-review marker is stale', async () => {
    const stale = definition('PT-A', 'PUBLISHED', 4, 44);
    stale.unitReviewRequired = true;
    apiMocks.getProductProcessWorkflow.mockResolvedValue({ success: true, data: stale });

    const wrapper = mountEditor();
    await flushPromises();

    const vm = wrapper.vm as unknown as {
      definition: ProductProcessWorkflowDefinition;
      dirty: boolean;
    };
    expect(vm.definition.status).toBe('PUBLISHED');
    expect(vm.definition.version).toBe(4);
    expect(vm.dirty).toBe(false);
    expect(wrapper.find('.unit-review-alert').exists()).toBe(false);
    // 已发布版本仍保留只读的 Workflow AI 辅助入口；它不允许改写画布。
    expect(wrapper.find('[aria-label="Workflow AI 助手"]').exists()).toBe(true);
    expect(apiMocks.saveProductProcessWorkflowDraft).not.toHaveBeenCalled();
  });

  // (移除) 旧「启用版本」独立按钮的 loading/generation 测试 —— #12a 已把启用并入发布,
  // 不再有独立启用按钮, 该 UI 路径不存在。发布→启用的一步行为由上面第一个测试覆盖。
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

function productOption(id: string, unit = 'kg') {
  return { id, name: id, unit, productCategory: 'FINISHED_GOOD', isActive: true };
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

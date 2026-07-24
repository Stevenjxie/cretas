import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElMessageBox } from 'element-plus';
import type { SeasoningWorkspace } from '@/api/bom';

const getWorkspace = vi.fn();
const createBinding = vi.fn();
const updateBinding = vi.fn();
const deleteBinding = vi.fn();
const requestGet = vi.fn();
const listSubstitutes = vi.fn();
const upgradeWorkflowRevision = vi.fn();
const routerPush = vi.fn();

vi.mock('@/api/bom', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/bom')>();
  return {
    ...original,
    bomSeasoningApi: {
      getWorkspace: (...args: unknown[]) => getWorkspace(...args),
      createBinding: (...args: unknown[]) => createBinding(...args),
      updateBinding: (...args: unknown[]) => updateBinding(...args),
      deleteBinding: (...args: unknown[]) => deleteBinding(...args),
    },
    bomRecipeApi: {
      ...original.bomRecipeApi,
      listSubstitutes: (...args: unknown[]) => listSubstitutes(...args),
      upgradeWorkflowRevision: (...args: unknown[]) => upgradeWorkflowRevision(...args),
    },
  };
});
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => requestGet(...args) }));
vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: (...args: unknown[]) => routerPush(...args),
    resolve: () => ({ href: '/warehouse/material-types' }),
  }),
  useRoute: () => ({ fullPath: '/production/bom?productTypeId=P1' }),
}));

import BomAuxiliaryWorkspace from '../BomAuxiliaryWorkspace.vue';

function workspace(status: 'DRAFT' | 'ACTIVE' = 'DRAFT'): SeasoningWorkspace {
  return {
    recipeId: 'R1', productTypeId: 'P1', productName: '猪蹄', status,
    editable: status === 'DRAFT', seasoningRevision: 4, anomalies: [], materialSummaries: [],
    workflowRevisionId: 101, workflowDefinitionVersion: 2, workflowRevisionHash: 'hash-101',
    workflowRevisionStatus: 'DRAFT', workflowRevisionSavedAt: '2026-07-20T12:30:00', workflowRootCount: 2,
    workflowProcessCount: 2, workflowTargetCount: 1,
    processes: [
      { workflowProcessNodeId: 'node-roll', workProcessId: 'ROLL', processOrder: 2, processName: '滚揉', standardBasisQuantity: 1, standardBasisUnit: 'kg', standardBasisMaterialKind: 'SEMI_FINISHED', standardUsageSupported: true, bindings: [{ id: 1, workflowProcessNodeId: 'node-roll', workProcessId: 'ROLL', materialTypeId: 'M1', name: '辣椒粉', unit: 'g', dosagePerKgG: 5, subsequentPotRatio: .5, countInSeasoning: true, priceSnapshot: 18 }] },
      { workflowProcessNodeId: 'node-fry', workProcessId: 'FRY', processOrder: 3, processName: '炸水', standardBasisQuantity: 1, standardBasisUnit: 'kg', standardBasisMaterialKind: 'SEMI_FINISHED', standardUsageSupported: true, bindings: [{ id: 2, workflowProcessNodeId: 'node-fry', workProcessId: 'FRY', materialTypeId: 'M1', name: '辣椒粉', unit: 'g', dosagePerKgG: 1.5, subsequentPotRatio: null, countInSeasoning: true, priceSnapshot: 18 }] },
    ],
  };
}

function mountWorkspace(status: 'DRAFT' | 'ACTIVE' = 'DRAFT', data = workspace(status)) {
  getWorkspace.mockResolvedValue({ success: true, data });
  requestGet.mockResolvedValue({ success: true, data: [{ id: 'M1', name: '辣椒粉', category: '调味料', unit: 'g', movingAvgPrice: 18 }] });
  listSubstitutes.mockResolvedValue({ success: true, data: [] });
  return mount(BomAuxiliaryWorkspace, {
    props: { factoryId: 'F006', productTypeId: 'P1', recipeId: 'R1', recipeStatus: status, canWrite: true },
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ElDialog: {
          props: ['modelValue', 'title'],
          template: '<div v-if="modelValue" class="dialog-stub"><slot /><slot name="footer" /></div>',
        },
      },
    },
  });
}

describe('BomAuxiliaryWorkspace', () => {
  beforeEach(() => vi.clearAllMocks());

  it('keeps the process editor and compact auxiliary summary visible as a two-column workspace', async () => {
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();

    const layout = wrapper.get('[data-testid="seasoning-two-column-layout"]');
    expect(wrapper.get('[data-testid="bom-workflow-source-card"]').text())
      .toContain('2 个投入入口2 道工序1 个终端产出');
    expect(layout.get('[data-testid="seasoning-editor-column"]').exists()).toBe(true);
    const compactSummary = layout.get('[data-testid="seasoning-compact-summary"]');
    expect(compactSummary.text()).toContain('辣椒粉');
    expect(compactSummary.text()).toContain('用于 2 个工序');
  });

  it('supports independent multi-expand plus one-click expand and collapse all', async () => {
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();
    const expandedLabels = () => wrapper.findAll('.process-card__chevron').map((label) => label.text());

    expect(expandedLabels()).toEqual(['收起', '展开']);
    await wrapper.get('[data-testid="toggle-all-processes"]').trigger('click');
    await flushPromises();
    expect(expandedLabels()).toEqual(['收起', '收起']);
    expect(wrapper.get('[data-testid="toggle-all-processes"]').text()).toContain('全部收起');

    await wrapper.get('[data-testid="seasoning-process-node-roll"] .process-card__header').trigger('click');
    await flushPromises();
    expect(expandedLabels()).toEqual(['展开', '收起']);

    await wrapper.get('[data-testid="toggle-all-processes"]').trigger('click');
    await flushPromises();
    expect(expandedLabels()).toEqual(['收起', '收起']);
    await wrapper.get('[data-testid="toggle-all-processes"]').trigger('click');
    await flushPromises();
    expect(expandedLabels()).toEqual(['展开', '展开']);
  });

  it('allows DRAFT editing, locks the dialog to the card process, and allows cross-process reuse', async () => {
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();
    expect(wrapper.findAll('[data-testid^="seasoning-process-"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('另用于 1 个工序');

    await wrapper.get('[data-testid="seasoning-process-node-roll"] [data-testid="add-seasoning-binding"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="locked-process-context"]').attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('滚揉');
    expect(wrapper.find('[data-testid="seasoning-binding-dialog"] .el-form-item:has(.el-select[placeholder*="工序"])').exists()).toBe(false);
  });

  it('fails closed when substitute relations cannot be loaded and never opens a mutation dialog', async () => {
    listSubstitutes.mockRejectedValueOnce(new Error('network down'));
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();

    expect(wrapper.get('[data-testid="substitute-load-error"]').text()).toContain('不会用空集合覆盖');
    expect(wrapper.find('[data-testid="add-seasoning-binding"]').exists()).toBe(false);
    expect(createBinding).not.toHaveBeenCalled();

    listSubstitutes.mockResolvedValue({ success: true, data: [] });
    await wrapper.get('[data-testid="retry-substitutes"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="substitute-load-error"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="add-seasoning-binding"]').exists()).toBe(true);
  });

  it('renders one shared process node once even when two raw roots reach it', async () => {
    const data = workspace('DRAFT');
    data.processes = [data.processes[0], { ...data.processes[0] }];
    const wrapper = mountWorkspace('DRAFT', data);
    await flushPromises();

    expect(wrapper.findAll('[data-testid="seasoning-process-node-roll"]')).toHaveLength(1);
    expect(wrapper.text()).toContain('1 条工序绑定');
  });

  it('deduplicates summary rows, exposes independent usages, navigates back, and has no summary add action', async () => {
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();
    const summaryTab = wrapper.findAll('.el-radio-button').find((node) => node.text().includes('辅料汇总'));
    if (!summaryTab) throw new Error('missing summary tab');
    await summaryTab.get('input').setValue('summary');
    await flushPromises();

    const summary = wrapper.get('[data-testid="seasoning-summary-view"]');
    expect(summary.text()).toContain('用于 2 个工序');
    expect(summary.text()).toContain('滚揉 5.0000 克/1千克');
    expect(summary.text()).toContain('炸水 1.5000 克/1千克');
    expect(summary.find('[data-testid="add-seasoning-binding"]').exists()).toBe(false);

    await summary.get('.el-table__expand-icon').trigger('click');
    await flushPromises();
    const locate = wrapper.findAll('button').find((button) => button.text().includes('定位到工序'));
    if (!locate) throw new Error('missing locate action');
    await locate.trigger('click');
    expect(wrapper.get('[data-testid="process-seasoning-view"]').exists()).toBe(true);
  });

  it('keeps ACTIVE read-only and emits clone guidance', async () => {
    const wrapper = mountWorkspace('ACTIVE');
    await flushPromises();
    expect(wrapper.find('[data-testid="add-seasoning-binding"]').exists()).toBe(false);
    await wrapper.get('[data-testid="request-clone"]').trigger('click');
    expect(wrapper.emitted('request-clone')).toHaveLength(1);
  });

  it('keeps family-shared processes read-only on a co-product while allowing its exclusive process', async () => {
    const data = workspace('DRAFT');
    data.sharedRulesOwner = false;
    data.processes[0].costScope = 'SHARED';
    data.processes[0].editable = false;
    data.processes[1].costScope = 'OUTPUT_EXCLUSIVE';
    data.processes[1].editable = true;
    const wrapper = mountWorkspace('DRAFT', data);
    await flushPromises();
    await wrapper.get('[data-testid="toggle-all-processes"]').trigger('click');
    await flushPromises();

    expect(wrapper.findAll('[data-testid="add-seasoning-binding"]')).toHaveLength(1);
    expect(wrapper.get('[data-testid="seasoning-process-node-roll"]').text()).toContain('家族共享');
    expect(wrapper.get('[data-testid="seasoning-process-node-fry"]').text()).toContain('本产出专属');
  });

  it('shows an automatic read-only process source and never exposes revision controls', async () => {
    const data = workspace('DRAFT');
    data.workflowOwnerProductTypeId = 'WORKFLOW-OWNER';
    const wrapper = mountWorkspace('DRAFT', data);
    await flushPromises();

    const source = wrapper.get('[data-testid="bom-workflow-source-card"]');
    expect(source.text()).toContain('工艺来源');
    expect(source.text()).toContain('Workflow v2');
    expect(source.text()).toContain('系统已根据当前 SKU 自动关联并固定该工艺版本');
    expect(wrapper.find('[data-testid="workflow-revision-select"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="pin-workflow-revision"]').exists()).toBe(false);

    await wrapper.get('[data-testid="view-workflow"]').trigger('click');
    expect(routerPush).toHaveBeenCalledWith({
      name: 'ProductProcesses',
      query: { productTypeId: 'WORKFLOW-OWNER' },
    });
  });

  it('offers an explicit upgrade that creates a new draft while preserving history', async () => {
    const data = workspace('ACTIVE');
    data.workflowUpgradeAvailable = true;
    data.workflowUpgradeDefinitionVersion = 3;
    upgradeWorkflowRevision.mockResolvedValue({
      success: true,
      data: { id: 'R2' },
    });
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const wrapper = mountWorkspace('ACTIVE', data);
    await flushPromises();

    expect(wrapper.get('[data-testid="workflow-update-notice"]').text())
      .toContain('升级会创建新 BOM 草稿');
    await wrapper.get('[data-testid="upgrade-workflow"]').trigger('click');
    await flushPromises();
    expect(upgradeWorkflowRevision).toHaveBeenCalledWith('F006', 'R1');
    expect(wrapper.emitted('workflow-upgraded')).toEqual([['R2']]);
  });

  it('fails closed with one actionable source error when no exact process version is pinned', async () => {
    const data = workspace('DRAFT');
    data.workflowRevisionHash = null;
    const wrapper = mountWorkspace('DRAFT', data);
    await flushPromises();

    expect(wrapper.get('[data-testid="workflow-source-error"]').text())
      .toContain('系统不会猜测或静默绑定版本');
    expect(wrapper.find('[data-testid="workflow-revision-select"]').exists()).toBe(false);
  });
});

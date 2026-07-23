import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import type { SeasoningWorkspace } from '@/api/bom';

const getWorkspace = vi.fn();
const createBinding = vi.fn();
const updateBinding = vi.fn();
const deleteBinding = vi.fn();
const requestGet = vi.fn();
const listWorkflowRevisions = vi.fn();
const listSubstitutes = vi.fn();
const pinWorkflowRevision = vi.fn();

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
      listWorkflowRevisions: (...args: unknown[]) => listWorkflowRevisions(...args),
      listSubstitutes: (...args: unknown[]) => listSubstitutes(...args),
      pinWorkflowRevision: (...args: unknown[]) => pinWorkflowRevision(...args),
    },
  };
});
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => requestGet(...args) }));
vi.mock('vue-router', () => ({
  useRouter: () => ({ resolve: () => ({ href: '/warehouse/material-types' }) }),
  useRoute: () => ({ fullPath: '/production/bom?productTypeId=P1' }),
}));

import BomAuxiliaryWorkspace from '../BomAuxiliaryWorkspace.vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const workspaceSource = readFileSync(resolve(import.meta.dirname, '../BomAuxiliaryWorkspace.vue'), 'utf8');

function workspace(status: 'DRAFT' | 'ACTIVE' = 'DRAFT'): SeasoningWorkspace {
  return {
    recipeId: 'R1', productTypeId: 'P1', productName: '猪蹄', status,
    editable: status === 'DRAFT', seasoningRevision: 4, anomalies: [], materialSummaries: [],
    workflowRevisionId: 101, workflowDefinitionVersion: 2, workflowRevisionHash: 'hash-101',
    workflowRevisionSavedAt: '2026-07-20T12:30:00', workflowRootCount: 2,
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
  listWorkflowRevisions.mockResolvedValue({ success: true, data: [] });
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
    expect(wrapper.get('[data-testid="bom-workflow-revision-card"]').text())
      .toContain('2个入口 / 2道工序 / 1个目标产出');
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

  it('does not preselect an incompatible workflow revision', async () => {
    listWorkflowRevisions.mockResolvedValueOnce({
      success: true,
      data: [{
        revisionId: 19,
        workflowId: 9,
        definitionVersion: 2,
        revisionHash: 'bad-hash',
        status: 'DRAFT',
        processCount: 2,
        compatible: false,
        incompatibilityReason: '目标成品不匹配',
      }],
    });
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();

    expect(wrapper.get('[data-testid="no-compatible-workflow-revision"]').text())
      .toContain('没有与本 SKU 兼容');
    expect(wrapper.findComponent('[data-testid="workflow-revision-select"]').props('modelValue'))
      .toBe('');
    expect(wrapper.get('[data-testid="pin-workflow-revision"]').attributes('disabled')).toBeDefined();
  });

  it('collapses internal saves into business versions and saves only a changed compatible version', async () => {
    listWorkflowRevisions.mockResolvedValueOnce({
      success: true,
      data: [
        {
          revisionId: 101, workflowId: 9, definitionVersion: 2, revisionNumber: 9,
          revisionHash: 'hash-101', status: 'DRAFT', processCount: 2, compatible: true,
          savedAt: '2026-07-20T12:30:00',
        },
        {
          revisionId: 102, workflowId: 9, definitionVersion: 2, revisionNumber: 10,
          revisionHash: 'hash-102', status: 'DRAFT', processCount: 2, compatible: true,
          savedAt: '2026-07-20T13:30:00',
        },
        {
          revisionId: 201, workflowId: 9, definitionVersion: 3, revisionNumber: 1,
          revisionHash: 'hash-201', status: 'DRAFT', processCount: 3, compatible: true,
          savedAt: '2026-07-21T08:00:00',
        },
      ],
    });
    pinWorkflowRevision.mockResolvedValue({ success: true, data: {} });
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();

    expect(workspaceSource).toContain('const byBusinessVersion = new Map');
    expect(workspaceSource).toContain('v-for="candidate in visibleWorkflowRevisions"');
    expect(wrapper.get('[data-testid="bom-workflow-revision-card"]').text()).not.toContain('固定此修订');
    const save = wrapper.get('[data-testid="pin-workflow-revision"]');
    expect(save.text()).toBe('保存');
    expect(save.attributes('disabled')).toBeDefined();

    wrapper.getComponent('[data-testid="workflow-revision-select"]').vm.$emit(
      'update:modelValue',
      'revision:201',
    );
    await flushPromises();
    expect(save.attributes('disabled')).toBeUndefined();
    await save.trigger('click');
    await flushPromises();
    expect(pinWorkflowRevision).toHaveBeenCalledWith('F006', 'R1', {
      revisionId: 201,
      workflowId: undefined,
      revisionHash: 'hash-201',
    });
  });
});

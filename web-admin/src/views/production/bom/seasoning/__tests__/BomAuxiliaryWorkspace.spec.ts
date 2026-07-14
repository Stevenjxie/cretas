import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import type { SeasoningWorkspace } from '@/api/bom';

const getWorkspace = vi.fn();
const createBinding = vi.fn();
const updateBinding = vi.fn();
const deleteBinding = vi.fn();
const requestGet = vi.fn();

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
  };
});
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => requestGet(...args) }));

import BomAuxiliaryWorkspace from '../BomAuxiliaryWorkspace.vue';

function workspace(status: 'DRAFT' | 'ACTIVE' = 'DRAFT'): SeasoningWorkspace {
  return {
    recipeId: 'R1', productTypeId: 'P1', productName: '猪蹄', status,
    editable: status === 'DRAFT', seasoningRevision: 4, anomalies: [], materialSummaries: [],
    processes: [
      { workProcessId: 'ROLL', processOrder: 2, processName: '滚揉', bindings: [{ id: 1, workProcessId: 'ROLL', materialTypeId: 'M1', name: '辣椒粉', unit: 'g', dosagePerKgG: 5, subsequentPotRatio: .5, countInSeasoning: true, priceSnapshot: 18 }] },
      { workProcessId: 'FRY', processOrder: 3, processName: '炸水', bindings: [{ id: 2, workProcessId: 'FRY', materialTypeId: 'M1', name: '辣椒粉', unit: 'g', dosagePerKgG: 1.5, subsequentPotRatio: null, countInSeasoning: true, priceSnapshot: 18 }] },
    ],
  };
}

function mountWorkspace(status: 'DRAFT' | 'ACTIVE' = 'DRAFT') {
  getWorkspace.mockResolvedValue({ success: true, data: workspace(status) });
  requestGet.mockResolvedValue({ success: true, data: [{ id: 'M1', name: '辣椒粉', category: '调味料', unit: 'g', movingAvgPrice: 18 }] });
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

  it('allows DRAFT editing, locks the dialog to the card process, and allows cross-process reuse', async () => {
    const wrapper = mountWorkspace('DRAFT');
    await flushPromises();
    expect(wrapper.findAll('[data-testid^="seasoning-process-"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('另用于 1 个工序');

    await wrapper.get('[data-testid="seasoning-process-ROLL"] [data-testid="add-seasoning-binding"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="locked-process-context"]').attributes('disabled')).toBeDefined();
    expect(wrapper.text()).toContain('滚揉');
    expect(wrapper.find('[data-testid="seasoning-binding-dialog"] .el-form-item:has(.el-select[placeholder*="工序"])').exists()).toBe(false);
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
    expect(summary.text()).toContain('滚揉 5.0000 g/kg');
    expect(summary.text()).toContain('炸水 1.5000 g/kg');
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
});

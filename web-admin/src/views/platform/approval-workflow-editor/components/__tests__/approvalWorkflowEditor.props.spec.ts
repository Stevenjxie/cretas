import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getDecisionTypes: vi.fn(),
  getDecisionTypesMetadata: vi.fn(),
  getWorkflowsByDecisionType: vi.fn(),
  getWorkflowById: vi.fn(),
  createWorkflow: vi.fn(),
  cloneWorkflowDraft: vi.fn(),
  updateWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
  publishWorkflow: vi.fn(),
  archiveWorkflow: vi.fn(),
  validateWorkflow: vi.fn(),
  getApprovalDirectory: vi.fn(),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));

vi.mock('@/api/approvalWorkflow', () => api);

import ApprovalWorkflowEditor from '../../index.vue';

describe('ApprovalWorkflowEditor decision type prop', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getDecisionTypes.mockResolvedValue({ success: true, data: [] });
    api.getDecisionTypesMetadata.mockResolvedValue({ success: true, data: [] });
    api.getWorkflowsByDecisionType.mockResolvedValue({ success: true, data: [] });
    api.getWorkflowById.mockResolvedValue({ success: false });
    api.getApprovalDirectory.mockResolvedValue({ roles: [], users: [] });
  });

  it('reloads workflow definitions when the deep-linked decision type changes', async () => {
    const wrapper = shallowMount(ApprovalWorkflowEditor, {
      props: {
        embedded: true,
        initialDecisionType: 'QUALITY_RELEASE',
      },
    });
    await flushPromises();

    expect(api.getWorkflowsByDecisionType).toHaveBeenCalledWith('F006', 'QUALITY_RELEASE');

    api.getWorkflowsByDecisionType.mockClear();
    await wrapper.setProps({ initialDecisionType: 'SALES_ORDER_APPROVAL' });
    await flushPromises();

    expect(api.getWorkflowsByDecisionType).toHaveBeenCalledTimes(1);
    expect(api.getWorkflowsByDecisionType).toHaveBeenCalledWith('F006', 'SALES_ORDER_APPROVAL');
  });

  it('loads the exact workflow and locks the business for approval-chain deep links', async () => {
    api.getWorkflowsByDecisionType.mockResolvedValue({
      success: true,
      data: [{
        id: 'purchase-flow',
        factoryId: 'F006',
        decisionType: 'PURCHASE_ORDER_APPROVAL',
        name: '采购订单审批',
        nodesJson: '[]',
        edgesJson: '[]',
        startNodeId: '',
        version: 3,
        publishStatus: 'published',
        enabled: true,
        priority: 10,
      }],
    });
    api.getWorkflowById.mockResolvedValue({
      success: true,
      data: {
        id: 'purchase-flow',
        factoryId: 'F006',
        decisionType: 'PURCHASE_ORDER_APPROVAL',
        name: '采购订单审批',
        nodesJson: '[]',
        edgesJson: '[]',
        startNodeId: '',
        version: 3,
        publishStatus: 'published',
        enabled: true,
        priority: 10,
      },
    });

    const wrapper = shallowMount(ApprovalWorkflowEditor, {
      props: {
        embedded: true,
        initialDecisionType: 'PURCHASE_ORDER_APPROVAL',
        initialWorkflowId: 'purchase-flow',
        lockDecisionType: true,
      },
    });
    await flushPromises();

    expect(api.getWorkflowsByDecisionType).toHaveBeenCalledWith(
      'F006',
      'PURCHASE_ORDER_APPROVAL',
    );
    expect(api.getWorkflowById).toHaveBeenCalledWith('F006', 'purchase-flow');
    expect(wrapper.text()).toContain('正在配置：');
    expect(wrapper.text()).toContain('审批已启用');
    expect(wrapper.text()).toContain('克隆为新版本');
  });
});

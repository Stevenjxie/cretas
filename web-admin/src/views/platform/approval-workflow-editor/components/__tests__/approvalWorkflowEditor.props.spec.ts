import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getDecisionTypes: vi.fn(),
  getDecisionTypesMetadata: vi.fn(),
  getWorkflowsByDecisionType: vi.fn(),
  getWorkflowById: vi.fn(),
  createWorkflow: vi.fn(),
  updateWorkflow: vi.fn(),
  deleteWorkflow: vi.fn(),
  publishWorkflow: vi.fn(),
  archiveWorkflow: vi.fn(),
  validateWorkflow: vi.fn(),
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
});

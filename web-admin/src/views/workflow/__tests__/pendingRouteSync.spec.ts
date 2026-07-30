import { flushPromises, shallowMount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  route: null as any,
  push: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  harness.route = reactive({
    query: {
      moduleCode: 'SALES_ORDER',
      instanceId: 'sales-instance',
    } as Record<string, string | undefined>,
  });
  // pending.vue 2026-07-30 起还用 useRouter(): 调拨审批通过后要引导回单据点「确认入库」
  // (客户「审核后 库存没有过来」)。mock 需同步补上, 否则组件挂载即报 No "useRouter" export。
  return { useRoute: () => harness.route, useRouter: () => ({ push: harness.push }) };
});

vi.mock('@/api/request', () => ({
  get: harness.get,
  post: harness.post,
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));

vi.mock('@/utils/enumDisplay', () => ({
  enumLabel: (value: string) => value,
}));

vi.mock('@/utils/errorToast', () => ({
  handleCatchError: vi.fn(),
}));

import PendingApprovals from '../pending.vue';

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: {
    data: { type: Array, default: () => [] },
    rowClassName: { type: Function, default: undefined },
  },
  template: '<div />',
});

describe('pending OA route query synchronization', () => {
  it('loads the requested module and keeps the instance deep link reactive', async () => {
    harness.get.mockResolvedValue({
      success: true,
      data: {
        items: [
          {
            instanceId: 'sales-instance',
            moduleCode: 'SALES_ORDER',
            businessEntityId: 'SO-1',
          },
        ],
        total: 1,
      },
    });

    const wrapper = shallowMount(PendingApprovals, {
      global: {
        stubs: {
          ElTable: ElTableStub,
          'el-table': ElTableStub,
        },
      },
    });
    await flushPromises();

    expect(harness.get).toHaveBeenCalledWith('/F006/workflow/instances/pending', {
      params: {
        page: 1,
        size: 20,
        moduleCode: 'SALES_ORDER',
      },
    });

    const table = wrapper.findComponent(ElTableStub);
    const rowClassName = table.props('rowClassName') as (context: {
      row: { instanceId: string };
    }) => string;
    expect(rowClassName({ row: { instanceId: 'sales-instance' } })).toBe(
      'deep-linked-approval-row',
    );
    expect(rowClassName({ row: { instanceId: 'another-instance' } })).toBe('');

    harness.route.query.instanceId = 'purchase-instance';
    await nextTick();
    expect(rowClassName({ row: { instanceId: 'purchase-instance' } })).toBe(
      'deep-linked-approval-row',
    );

    harness.route.query.moduleCode = 'PURCHASE_ORDER';
    await flushPromises();
    expect(harness.get).toHaveBeenLastCalledWith('/F006/workflow/instances/pending', {
      params: {
        page: 1,
        size: 20,
        moduleCode: 'PURCHASE_ORDER',
      },
    });

    wrapper.unmount();
  });
});

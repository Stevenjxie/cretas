/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 (Phase F) — OrdersTab spec.
 * 重点: fetch on mount with correct params + state machine transitions.
 * Mask DOM rendering tested in Phase G E2E (el-table-column scoped slot
 * doesn't render cleanly with vitest stubs).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import OrdersTab from '../OrdersTab.vue';

const mockGet = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: any[]) => mockGet(...args),
}));

const canViewPriceRef = ref(true);
const factoryIdRef = ref('F999');

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: factoryIdRef }),
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: canViewPriceRef }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const globalStubs = {
  'el-skeleton': { props: ['rows', 'animated'], template: '<div class="el-skeleton" />' },
  'el-empty': { props: ['description', 'imageSize'], template: '<div class="el-empty">{{ description }}<slot /></div>' },
  'el-table': {
    props: ['data', 'border', 'stripe', 'size'],
    template: '<div class="el-table-stub" :data-count="data?.length || 0"><slot /></div>',
  },
  'el-table-column': { props: ['label', 'prop'], template: '<div class="col" />' },
  'el-pagination': { template: '<div class="el-pagination" />' },
  'el-button': { props: ['type', 'loading'], template: '<button class="el-button"><slot /></button>' },
  'el-icon': { template: '<i />' },
  'el-result': { props: ['icon', 'title'], template: '<div class="el-result">{{ title }}<slot name="extra" /></div>' },
};

describe('OrdersTab', () => {
  beforeEach(() => {
    mockGet.mockReset();
    canViewPriceRef.value = true;
  });

  it('fetches /sales/orders/by-customer with customerId param', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: { content: [{ id: '1', orderNumber: 'SO-001' }], totalElements: 1 },
    });
    mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(mockGet).toHaveBeenCalledWith(
      '/F999/sales/orders/by-customer',
      expect.objectContaining({ params: expect.objectContaining({ customerId: 'cust-1' }) }),
    );
  });

  it('transitions to empty state when no orders', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: { content: [], totalElements: 0 },
    });
    const w = mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-empty').exists()).toBe(true);
    expect(w.html()).toContain('该客户暂无销售单');
  });

  it('transitions to ready state with data', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: {
        content: [{ id: '1', orderNumber: 'SO-001', totalAmount: 100 }],
        totalElements: 1,
      },
    });
    const w = mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    const table = w.find('.el-table-stub');
    expect(table.exists()).toBe(true);
    expect(table.attributes('data-count')).toBe('1');
    expect(w.find('.el-empty').exists()).toBe(false);
  });

  it('transitions to error state on 403', async () => {
    mockGet.mockRejectedValueOnce({ response: { status: 403, data: { message: '无权限' } } });
    const w = mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-result').exists()).toBe(true);
    expect(w.html()).toContain('无权');
  });
});

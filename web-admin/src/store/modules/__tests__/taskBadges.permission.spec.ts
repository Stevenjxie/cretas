/**
 * 角标不许发「当前账号没权限读」的请求。
 *
 * 背景(2026-08-17, 报工审批页实测): 文员打开 /production/approval, console 里
 * 连续三条 `/sales/orders` 403。那不是审批页发的 —— 是侧边栏角标 store 对所有人
 * 一视同仁地发三个请求, 而文员没有 sales 读权限。
 *
 * 请求带 `_silent: true` 所以不弹 toast, 但 403 照样进 console, 服务端照样被打。
 * ⇒ 判据是「【没发】那个请求」, ⛔ 不是「发了但把错误吞掉」。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const getMock = vi.fn();
const receivingMock = vi.fn();
const arrivalsMock = vi.fn();
const canAccessMock = vi.fn();

vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => getMock(...args) }));
vi.mock('@/api/purchaseReceive', () => ({
  getPendingWarehouseReceivingTasks: (...args: unknown[]) => receivingMock(...args),
}));
vi.mock('@/api/customerMaterialArrival', () => ({
  listCustomerMaterialArrivals: (...args: unknown[]) => arrivalsMock(...args),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: canAccessMock }),
}));

import { useTaskBadgeStore } from '../taskBadges';

const okPage = (content: unknown[]) => ({
  success: true,
  data: { content, totalElements: content.length },
});

describe('taskBadges — 没权限的模块不发请求', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getMock.mockReset().mockResolvedValue(okPage([]));
    receivingMock.mockReset().mockResolvedValue({ success: true, data: [] });
    arrivalsMock.mockReset().mockResolvedValue({ success: true, data: [] });
    canAccessMock.mockReset();
  });

  it('没有 sales 读权限 ⇒ 一次 /sales/orders 都不发', async () => {
    canAccessMock.mockImplementation((m: string) => m !== 'sales');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    // 判据钉在【那个 URL 上】—— 「get 被调用了几次」不够精确,
    // 别的角标也走同一个 get。
    const salesCalls = getMock.mock.calls.filter(
      (args) => String(args[0]).includes('/sales/orders'));
    expect(salesCalls).toHaveLength(0);

    // 角标本身仍然是隐藏的(fail-closed), 与「请求失败」时的观感一致
    expect(store.badge('salesOrders')).toBeNull();

    // 阳性对照: 有权限的那两个照常发 —— 否则这条断言可能只是「整个 loadAll 没跑」
    expect(receivingMock).toHaveBeenCalledTimes(1);
    expect(arrivalsMock).toHaveBeenCalledTimes(1);
  });

  it('有 sales 读权限 ⇒ 照常发, 且角标算得出来', async () => {
    canAccessMock.mockReturnValue(true);
    getMock.mockResolvedValue(okPage([
      { status: 'PENDING' },
      { status: 'COMPLETED' }, // 终态, 不计入
      { status: 'CONFIRMED' },
    ]));

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    const salesCalls = getMock.mock.calls.filter(
      (args) => String(args[0]).includes('/sales/orders'));
    expect(salesCalls).toHaveLength(1);
    expect(store.badge('salesOrders')).toBe(2);
  });
});

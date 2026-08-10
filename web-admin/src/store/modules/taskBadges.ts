import { computed, reactive, ref } from 'vue';
import { defineStore } from 'pinia';
import { get } from '@/api/request';
import { getPendingWarehouseReceivingTasks } from '@/api/purchaseReceive';
import { listCustomerMaterialArrivals } from '@/api/customerMaterialArrival';
import type { TableRow } from '@/types/api';

export type TaskBadgeKey = 'salesOrders' | 'warehouseReceiving' | 'unorderedInbound';

interface BadgeState {
  count: number;
  available: boolean;
}

const TERMINAL_SALES_STATUSES = new Set([
  'COMPLETED', 'CLOSED', 'CANCELLED', 'REJECTED', 'REFUNDED',
]);

export function countActionableSalesOrders(rows: TableRow[]): number {
  return rows.filter((row) =>
    !TERMINAL_SALES_STATUSES.has(String(row.status || '').toUpperCase())).length;
}

export const useTaskBadgeStore = defineStore('taskBadges', () => {
  const factoryId = ref('');
  const loading = ref(false);
  const states = reactive<Record<TaskBadgeKey, BadgeState>>({
    salesOrders: { count: 0, available: false },
    warehouseReceiving: { count: 0, available: false },
    unorderedInbound: { count: 0, available: false },
  });
  let loadToken = 0;

  const totalActionable = computed(() => Object.values(states)
    .filter((state) => state.available)
    .reduce((sum, state) => sum + state.count, 0));

  function setFactory(nextFactoryId: string): void {
    const normalized = String(nextFactoryId || '').trim();
    if (factoryId.value === normalized) return;
    factoryId.value = normalized;
    for (const state of Object.values(states)) {
      state.count = 0;
      state.available = false;
    }
  }

  async function loadSales(currentFactoryId: string): Promise<number> {
    const response = await get(`/${currentFactoryId}/sales/orders`, {
      params: { page: 1, size: 500 },
      _silent: true,
    } as never);
    if (!response.success) throw new Error(response.message || 'Sales order task badge unavailable');
    const rows = Array.isArray(response.data?.content)
      ? response.data.content as TableRow[]
      : Array.isArray(response.data) ? response.data as TableRow[] : [];
    const totalElements = Number(response.data?.totalElements);
    if (Number.isFinite(totalElements) && totalElements > rows.length) {
      throw new Error('Sales order task badge requires a complete result set');
    }
    return countActionableSalesOrders(rows);
  }

  async function loadReceiving(currentFactoryId: string): Promise<number> {
    const response = await getPendingWarehouseReceivingTasks(currentFactoryId);
    if (!response.success) throw new Error(response.message || 'Warehouse receiving task badge unavailable');
    return Array.isArray(response.data) ? response.data.length : 0;
  }

  async function loadUnorderedInbound(currentFactoryId: string): Promise<number> {
    const response = await listCustomerMaterialArrivals(currentFactoryId, false);
    if (!response.success) throw new Error(response.message || 'Unordered inbound task badge unavailable');
    return Array.isArray(response.data)
      ? response.data.filter((row) => row.status === 'PENDING_APPROVAL').length
      : 0;
  }

  async function loadAll(): Promise<void> {
    const currentFactoryId = factoryId.value;
    if (!currentFactoryId) return;
    const token = ++loadToken;
    loading.value = true;
    const loaders: Array<[TaskBadgeKey, Promise<number>]> = [
      ['salesOrders', loadSales(currentFactoryId)],
      ['warehouseReceiving', loadReceiving(currentFactoryId)],
      ['unorderedInbound', loadUnorderedInbound(currentFactoryId)],
    ];
    const results = await Promise.allSettled(loaders.map(([, promise]) => promise));
    if (token !== loadToken || currentFactoryId !== factoryId.value) return;
    results.forEach((result, index) => {
      const key = loaders[index][0];
      if (result.status === 'fulfilled') {
        states[key].count = Math.max(0, Number(result.value) || 0);
        states[key].available = true;
      } else {
        // Fail closed: hide an unavailable badge instead of showing a misleading zero.
        states[key].available = false;
      }
    });
    loading.value = false;
  }

  function badge(key?: TaskBadgeKey): number | null {
    if (!key || !states[key].available || states[key].count <= 0) return null;
    return states[key].count;
  }

  return { states, loading, totalActionable, setFactory, loadAll, badge };
});

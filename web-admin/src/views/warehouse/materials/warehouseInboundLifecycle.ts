import type { WarehouseReceivingTask } from '@/api/purchaseReceive';

export type ReceivingTaskLifecycle = 'WAITING_RECEIVE' | 'RECEIVING' | 'PARTIAL';
export type ReceivingTaskFilter = 'ALL' | ReceivingTaskLifecycle;

export interface ReceivingLifecycleCounts {
  ALL: number;
  WAITING_RECEIVE: number;
  RECEIVING: number;
  PARTIAL: number;
}

function number(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

export function receivingTaskLifecycle(task: WarehouseReceivingTask): ReceivingTaskLifecycle {
  const status = String(task.status || '').toUpperCase();
  const received = (task.items || []).reduce((sum, item) => sum + number(item.receivedQuantity), 0);
  const remaining = (task.items || []).reduce((sum, item) => sum + number(item.remainingReceivableQuantity), 0);
  if (status === 'PARTIALLY_RECEIVED' || (received > 0 && remaining > 0)) return 'PARTIAL';
  if (number(task.activeReceiptCount) > 0 || status === 'RECEIVING') return 'RECEIVING';
  return 'WAITING_RECEIVE';
}

export function receivingLifecycleCounts(tasks: WarehouseReceivingTask[]): ReceivingLifecycleCounts {
  const counts: ReceivingLifecycleCounts = {
    ALL: tasks.length,
    WAITING_RECEIVE: 0,
    RECEIVING: 0,
    PARTIAL: 0,
  };
  for (const task of tasks) counts[receivingTaskLifecycle(task)] += 1;
  return counts;
}

export function filterReceivingTasks(
  tasks: WarehouseReceivingTask[],
  filter: ReceivingTaskFilter,
): WarehouseReceivingTask[] {
  return filter === 'ALL'
    ? tasks
    : tasks.filter((task) => receivingTaskLifecycle(task) === filter);
}

export function receivingLifecycleLabel(task: WarehouseReceivingTask): string {
  const lifecycle = receivingTaskLifecycle(task);
  if (lifecycle === 'PARTIAL') return '部分入库';
  if (lifecycle === 'RECEIVING') return '收货中';
  return '待收货';
}

export type SalesOrderLifecycle =
  | 'all'
  | 'todo'
  | 'reviewing'
  | 'ready'
  | 'fulfilling'
  | 'completed'
  | 'closed';

export type SalesOrderShipmentState = 'all' | 'UNSHIPPED' | 'PARTIAL' | 'SHIPPED';
export type SalesOrderPaymentState = 'all' | 'UNPAID' | 'PARTIAL' | 'PAID';

export type SalesOrderPrimaryAction =
  | 'submit-review'
  | 'view-review'
  | 'create-delivery'
  | 'continue-delivery'
  | 'view-detail';

export interface SalesOrderListLike {
  status?: unknown;
  paymentStatus?: unknown;
  totalAmount?: unknown;
  actualShippedAmount?: unknown;
  paidAmount?: unknown;
}

export interface SalesOrderListFilters {
  lifecycle: SalesOrderLifecycle;
  shipment: SalesOrderShipmentState;
  payment: SalesOrderPaymentState;
}

export const SALES_ORDER_LIFECYCLE_TABS: ReadonlyArray<{
  key: SalesOrderLifecycle;
  label: string;
}> = [
  { key: 'all', label: '全部' },
  { key: 'todo', label: '待处理' },
  { key: 'reviewing', label: '审批中' },
  { key: 'ready', label: '待履约' },
  { key: 'fulfilling', label: '履约中' },
  { key: 'completed', label: '已完成' },
  { key: 'closed', label: '已关闭' },
];

const TODO_STATUSES = new Set(['DRAFT', 'FINANCE_REJECTED']);
const REVIEWING_STATUSES = new Set(['CONFIRMED', 'PENDING_APPROVAL', 'PENDING_FINANCE_REVIEW']);
const READY_STATUSES = new Set(['APPROVED', 'FINANCE_APPROVED']);
const FULFILLING_STATUSES = new Set(['PROCESSING', 'IN_PRODUCTION', 'PARTIAL_DELIVERED', 'SHIPPED']);
const COMPLETED_STATUSES = new Set(['COMPLETED']);
const CLOSED_STATUSES = new Set(['CANCELLED', 'CLOSED']);

function normalized(value: unknown): string {
  return String(value ?? '').trim().toUpperCase();
}

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const result = Number(value);
  return Number.isFinite(result) ? result : null;
}

/**
 * Each backend status belongs to exactly one lifecycle bucket. Unknown/legacy
 * values fail into "待处理" instead of disappearing from every category.
 */
export function salesOrderLifecycleOf(row: SalesOrderListLike): Exclude<SalesOrderLifecycle, 'all'> {
  const status = normalized(row.status);
  if (TODO_STATUSES.has(status)) return 'todo';
  if (REVIEWING_STATUSES.has(status)) return 'reviewing';
  if (READY_STATUSES.has(status)) return 'ready';
  if (FULFILLING_STATUSES.has(status)) return 'fulfilling';
  if (COMPLETED_STATUSES.has(status)) return 'completed';
  if (CLOSED_STATUSES.has(status)) return 'closed';
  return 'todo';
}

/**
 * Prefer the order lifecycle because price-sensitive amounts may be stripped
 * for warehouse/read-only roles. Amounts are only a legacy fallback.
 */
export function salesOrderShipmentStateOf(row: SalesOrderListLike): Exclude<SalesOrderShipmentState, 'all'> {
  const status = normalized(row.status);
  if (status === 'COMPLETED' || status === 'SHIPPED') return 'SHIPPED';
  if (status === 'PARTIAL_DELIVERED') return 'PARTIAL';

  const shipped = finiteNumber(row.actualShippedAmount);
  const total = finiteNumber(row.totalAmount);
  if (shipped !== null && total !== null && total > 0) {
    if (shipped >= total) return 'SHIPPED';
    if (shipped > 0) return 'PARTIAL';
  }
  return 'UNSHIPPED';
}

export function salesOrderPaymentStateOf(row: SalesOrderListLike): Exclude<SalesOrderPaymentState, 'all'> {
  const serverState = normalized(row.paymentStatus);
  if (serverState === 'PAID' || serverState === 'PARTIAL' || serverState === 'UNPAID') {
    return serverState;
  }

  const paid = finiteNumber(row.paidAmount);
  const total = finiteNumber(row.totalAmount);
  if (paid !== null && total !== null && total > 0) {
    if (paid >= total) return 'PAID';
    if (paid > 0) return 'PARTIAL';
  }
  return 'UNPAID';
}

export function matchesSalesOrderListFilters(
  row: SalesOrderListLike,
  filters: SalesOrderListFilters,
): boolean {
  const lifecycleMatches = filters.lifecycle === 'all'
    || salesOrderLifecycleOf(row) === filters.lifecycle;
  const shipmentMatches = filters.shipment === 'all'
    || salesOrderShipmentStateOf(row) === filters.shipment;
  const paymentMatches = filters.payment === 'all'
    || salesOrderPaymentStateOf(row) === filters.payment;
  return lifecycleMatches && shipmentMatches && paymentMatches;
}

export function salesOrderLifecycleCounts(
  rows: SalesOrderListLike[],
): Record<SalesOrderLifecycle, number> {
  const result: Record<SalesOrderLifecycle, number> = {
    all: rows.length,
    todo: 0,
    reviewing: 0,
    ready: 0,
    fulfilling: 0,
    completed: 0,
    closed: 0,
  };
  for (const row of rows) result[salesOrderLifecycleOf(row)] += 1;
  return result;
}

export function salesOrderPrimaryActionOf(row: SalesOrderListLike): SalesOrderPrimaryAction {
  const status = normalized(row.status);
  if (status === 'DRAFT' || status === 'CONFIRMED' || status === 'FINANCE_REJECTED') {
    return 'submit-review';
  }
  if (status === 'PENDING_APPROVAL' || status === 'PENDING_FINANCE_REVIEW') {
    return 'view-review';
  }
  if (status === 'FINANCE_APPROVED' || status === 'PROCESSING') {
    return 'create-delivery';
  }
  if (status === 'PARTIAL_DELIVERED') return 'continue-delivery';
  return 'view-detail';
}

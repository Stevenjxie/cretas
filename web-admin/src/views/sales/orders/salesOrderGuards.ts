export type RowLike = Record<string, unknown>;

const NON_EFFECTIVE_STATUSES = new Set(['CANCELLED', 'REJECTED', 'RETURNED']);
const ACTIVE_PRODUCTION_STATUSES = new Set(['PLANNED', 'PREPARED', 'PENDING', 'IN_PROGRESS', 'PAUSED', 'PENDING_APPROVAL']);
const ACTIONABLE_DELIVERY_STATUSES = new Set([
  'DRAFT', 'PENDING_WAREHOUSE_CONFIRM', 'PENDING_SPLIT', 'PARTIALLY_SCHEDULED',
  'FULLY_SCHEDULED', 'PARTIALLY_SHIPPED', 'PICKED', 'SHIPPED',
]);

export interface ActionState {
  kind: 'ready' | 'in_progress' | 'completed' | 'loading' | 'error';
  label: string;
  disabled: boolean;
  tooltip: string;
  relatedId?: string;
}

export interface DeliveryCapacityLine {
  itemId: string;
  productTypeId: string;
  ordered: number;
  arranged: number;
  shipped: number;
  remaining: number;
  unit: string;
}

export type DeliveryMethod = 'LOGISTICS' | 'SELF_DELIVERY' | 'SELF_PICKUP';

function quantity(value: unknown): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}

function isEffective(status: unknown): boolean {
  return !NON_EFFECTIVE_STATUSES.has(String(status || '').toUpperCase());
}

function lineIdentity(row: RowLike): string {
  return String(row.salesOrderItemId ?? row.sourceOrderItemId ?? row.id ?? '');
}

function deliveryItemMatches(orderItem: RowLike, deliveryItem: RowLike, sameSkuCount: number): boolean {
  const orderItemId = String(orderItem.id ?? '');
  const persistedLineId = String(deliveryItem.salesOrderItemId ?? deliveryItem.sourceOrderItemId ?? '');
  if (persistedLineId) return persistedLineId === orderItemId;
  return sameSkuCount === 1 && String(deliveryItem.productTypeId ?? '') === String(orderItem.productTypeId ?? '');
}

export function deliveryCapacityByLine(orderItems: RowLike[], deliveries: RowLike[]): DeliveryCapacityLine[] {
  const skuCounts = new Map<string, number>();
  orderItems.forEach((item) => {
    const sku = String(item.productTypeId ?? '');
    skuCounts.set(sku, (skuCounts.get(sku) ?? 0) + 1);
  });

  return orderItems.map((item) => {
    const ordered = quantity(item.quantity);
    const shipped = Math.min(ordered, quantity(item.deliveredQuantity));
    const sku = String(item.productTypeId ?? '');
    let arranged = 0;
    deliveries.filter((delivery) => isEffective(delivery.status))
      .filter((delivery) => !delivery.parentDeliveryId && String(delivery.recordRole ?? '') !== 'SHIPMENT')
      .forEach((delivery) => {
      const deliveryItems = Array.isArray(delivery.items) ? delivery.items as RowLike[] : [];
      deliveryItems.forEach((deliveryItem) => {
        if (deliveryItemMatches(item, deliveryItem, skuCounts.get(sku) ?? 0)) {
          arranged += quantity(deliveryItem.deliveredQuantity ?? deliveryItem.plannedQuantity);
        }
      });
      });
    arranged = Math.min(ordered, arranged);
    return {
      itemId: String(item.id ?? ''),
      productTypeId: sku,
      ordered,
      arranged,
      shipped,
      remaining: Math.max(0, ordered - arranged),
      unit: String(item.unit ?? ''),
    };
  });
}

export function deliveryActionState(orderItems: RowLike[], deliveries: RowLike[], loading = false, failed = false): ActionState {
  if (loading) return { kind: 'loading', label: '核对发货安排', disabled: true, tooltip: '正在核对已有发货单' };
  if (failed) return { kind: 'error', label: '发货状态待确认', disabled: true, tooltip: '已有发货单加载失败，请刷新后重试' };
  const capacity = deliveryCapacityByLine(orderItems, deliveries);
  if (capacity.length > 0 && capacity.every((line) => line.shipped >= line.ordered)) {
    return { kind: 'completed', label: '已全部发货', disabled: true, tooltip: '订单数量已全部实际发货' };
  }
  if (capacity.length > 0 && capacity.every((line) => line.remaining <= 0)) {
    return { kind: 'completed', label: '已全部安排发货', disabled: true, tooltip: '订单数量已全部生成有效发货单' };
  }
  return { kind: 'ready', label: '新建发货单', disabled: false, tooltip: '' };
}

function isShipmentRecord(row: RowLike): boolean {
  return Boolean(row.parentDeliveryId) || String(row.recordRole || '').toUpperCase() === 'SHIPMENT';
}

/** Red badge means work still requiring an operator action, never historical record count. */
export function actionableDeliveryCount(deliveries: RowLike[]): number {
  const hasShipmentModel = deliveries.some((row) => isShipmentRecord(row));
  return deliveries.filter((row) => {
    if (hasShipmentModel && !isShipmentRecord(row)) return false;
    return ACTIONABLE_DELIVERY_STATUSES.has(String(row.status || '').toUpperCase());
  }).length;
}

export function deliveryTransportAggregate(deliveries: RowLike[]): 'PLANNING' | 'IN_TRANSIT' | 'PARTIALLY_RECEIVED' | 'RECEIVED' {
  const effective = deliveries.filter((row) => isEffective(row.status));
  const shipments = effective.some((row) => isShipmentRecord(row))
    ? effective.filter((row) => isShipmentRecord(row))
    : effective;
  if (shipments.length === 0) return 'PLANNING';
  const received = shipments.filter((row) => String(row.status || '').toUpperCase() === 'DELIVERED').length;
  if (received === shipments.length) return 'RECEIVED';
  if (received > 0) return 'PARTIALLY_RECEIVED';
  if (shipments.some((row) => ['SHIPPED', 'PARTIALLY_SHIPPED'].includes(String(row.status || '').toUpperCase()))) {
    return 'IN_TRANSIT';
  }
  return 'PLANNING';
}

export function shipmentValidationError(input: {
  deliveryMethod?: unknown;
  plannedShipmentDate?: unknown;
  logisticsCompany?: unknown;
  trackingNumber?: unknown;
}): string {
  if (!String(input.plannedShipmentDate || '').trim()) return '请选择计划发运日期';
  const method = String(input.deliveryMethod || 'LOGISTICS').toUpperCase() as DeliveryMethod;
  if (method === 'LOGISTICS' && !String(input.logisticsCompany || '').trim()) return '物流配送必须填写物流公司';
  if (method === 'LOGISTICS' && !String(input.trackingNumber || '').trim()) return '物流配送必须填写物流/运单号';
  return '';
}

export function productionActionState(orderItems: RowLike[], plans: RowLike[], loading = false, failed = false): ActionState {
  if (loading) return { kind: 'loading', label: '核对生产状态', disabled: true, tooltip: '正在核对关联生产计划' };
  if (failed) return { kind: 'error', label: '生产状态待确认', disabled: true, tooltip: '关联生产计划加载失败，请刷新后重试' };
  const effectivePlans = plans.filter((plan) => isEffective(plan.status));
  const covered = new Map<string, number>();
  effectivePlans.forEach((plan) => {
    const id = lineIdentity(plan);
    if (id) covered.set(id, (covered.get(id) ?? 0) + quantity(plan.sourceDisplayQuantity ?? plan.plannedQuantity));
  });
  const allCovered = orderItems.length > 0 && orderItems.every((item) => {
    const id = String(item.id ?? '');
    return (covered.get(id) ?? 0) >= quantity(item.quantity);
  });
  if (!allCovered) return { kind: 'ready', label: '开始生产', disabled: false, tooltip: '' };
  const active = effectivePlans.find((plan) => ACTIVE_PRODUCTION_STATUSES.has(String(plan.status ?? '').toUpperCase()));
  if (active) {
    return { kind: 'in_progress', label: '生产中', disabled: true, tooltip: '该订单已创建生产计划，正在生产', relatedId: String(active.id ?? '') };
  }
  const completed = effectivePlans.find((plan) => String(plan.status ?? '').toUpperCase() === 'COMPLETED');
  return { kind: 'completed', label: '已生产', disabled: true, tooltip: '该订单已完成生产', relatedId: String(completed?.id ?? effectivePlans[0]?.id ?? '') };
}

export function resolveDeliveryAddress(orderAddress: unknown, customerAddress: unknown): string {
  const explicit = String(orderAddress ?? '').trim();
  if (explicit) return explicit;
  return String(customerAddress ?? '').trim();
}

export function roundMoney(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

export function deliveryMoney(items: RowLike[]): { untaxed: number; tax: number; taxIncluded: number } {
  return items.reduce((sum, item) => {
    const untaxed = roundMoney(quantity(item.deliveredQuantity) * quantity(item.unitPrice));
    const tax = roundMoney(untaxed * quantity(item.taxRate) / 100);
    return {
      untaxed: roundMoney(sum.untaxed + untaxed),
      tax: roundMoney(sum.tax + tax),
      taxIncluded: roundMoney(sum.taxIncluded + untaxed + tax),
    };
  }, { untaxed: 0, tax: 0, taxIncluded: 0 });
}

export function formatBusinessDateTime(value: unknown): string {
  const raw = String(value ?? '').trim();
  if (!raw) return '-';
  const matched = raw.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/);
  if (!matched) return '-';
  const [, year, month, day, hour, minute, second = '00'] = matched;
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
  if (Number.isNaN(date.getTime())) return '-';
  const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getUTCDay()];
  return `${year}-${month}-${day}（${weekday}）${hour}:${minute}:${second}`;
}

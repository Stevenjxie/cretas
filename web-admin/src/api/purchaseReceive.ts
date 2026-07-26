// 采购收货 API client — 单元 G (F006 R-B3, 2026-06-02).
//
// Backend endpoints (PurchaseController):
//   GET /api/mobile/{factoryId}/purchase/orders/{orderId}/cumulative-received  (累计已收, 跨页权威值)
//   GET /api/mobile/{factoryId}/purchase/orders/{orderId}/receives            (分次收货时序明细)
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
// 同 orderCopy.ts 模式 (不带 /mobile/ 前缀).
import { get, post } from './request';
import type { FactoryWarehouse } from './factoryWarehouse';

/** 累计已收 — 单行明细 (后端 PurchaseServiceImpl.getCumulativeReceived lines). */
export interface CumulativeReceivedLine {
  materialId: string;
  materialName: string;
  plannedQty: number;
  receivedQty: number;
  pendingQty: number;
  unit: string;
}

/** 累计已收汇总 (Issue #787 endpoint). 替代 FE-only page-rows 聚合, 跨页准确. */
export interface CumulativeReceived {
  poId: string;
  orderNumber: string;
  plannedTotal: number;
  cumulativeReceived: number;
  lines: CumulativeReceivedLine[];
}

/** 分次收货 — 单次事件内的物料明细行. */
export interface ReceiveSequenceItem {
  materialName: string;
  quantity: number;
  unit: string;
}

/** 分次收货 — 单次收货事件 (单元 G). */
export interface ReceiveSequenceEntry {
  seq: number;
  receiveId: string;
  receiveNumber: string;
  receiveDate: string;
  createdAt: string;
  createdByName: string | null;
  totalQuantity: number;
  items: ReceiveSequenceItem[];
}

export interface PurchaseReceivingTaskItem {
  purchaseOrderItemId?: number | null;
  salesOrderItemId?: number | null;
  materialTypeId: string;
  materialName: string;
  orderedQuantity: number;
  receivedQuantity: number;
  activeDraftAllocatedQuantity: number;
  remainingReceivableQuantity: number;
  unit: string;
  specification?: string | null;
  materialPackagingSpecId?: string | null;
  inventoryBaseUnit?: string | null;
  packageToBaseFactor?: number | null;
  packagingSpecs?: Array<{
    id: string;
    name: string;
    packageUnit: string;
    baseUnit: string;
    conversionFactor: number;
    defaultSpec?: boolean | null;
    active?: boolean | null;
  }>;
}

export interface PurchaseReceivingTask {
  taskId: string;
  sourceType: 'PURCHASE';
  purchaseOrderId: string;
  orderNumber: string;
  supplierId: string;
  supplierName?: string | null;
  expectedDeliveryDate?: string | null;
  status: 'WAITING_RECEIVE' | 'RECEIVING';
  statusLabel: string;
  warehouseId?: string | null;
  warehouseName?: string | null;
  responsibleName?: string | null;
  activeReceiptId?: string | null;
  activeReceiptNumber?: string | null;
  activeReceiptCount: number;
  receiptConflict: boolean;
  items: PurchaseReceivingTaskItem[];
}

export interface CustomerSuppliedReceivingTask {
  taskId: string;
  sourceType: 'SALES_ORDER_CUSTOMER_SUPPLIED';
  salesOrderId: string;
  salesOrderNo: string;
  customerId: string;
  customerName: string;
  expectedArrivalAt?: string | null;
  status: 'WAITING_RECEIVE' | 'RECEIVING';
  statusLabel: string;
  warehouseId?: string | null;
  warehouseName?: string | null;
  responsibleName?: string | null;
  activeReceiptId?: string | null;
  activeReceiptNumber?: string | null;
  activeReceiptCount: number;
  receiptConflict: boolean;
  items: PurchaseReceivingTaskItem[];
}

export type WarehouseReceivingTask = PurchaseReceivingTask | CustomerSuppliedReceivingTask;

export interface WarehouseReceivingTaskFilters {
  purchaseOrderId?: string;
  orderNumber?: string;
  salesOrderId?: string;
  salesOrderNo?: string;
  sourceType?: 'PURCHASE' | 'SALES_ORDER_CUSTOMER_SUPPLIED';
}

export interface CreateCustomerSuppliedReceiptRequest {
  receivedQuantity: number;
  unit?: string;
  externalBatchNumber?: string;
  productionDate?: string;
  expireDate?: string;
  originPlace?: string;
  notes?: string;
  idempotencyKey: string;
}

export interface CustomerSuppliedReceiptResult {
  id: string;
  batchNumber?: string | null;
  quantity?: number | null;
  currentQuantity?: number | null;
  unit?: string | null;
}

export type PurchaseReceivingCloseReason =
  | 'SUPPLIER_SHORT_SHIPMENT'
  | 'QUALITY_REJECTION'
  | 'PURCHASE_BALANCE_CANCELLED'
  | 'DEMAND_CHANGED'
  | 'OTHER';

export interface ClosePurchaseReceivingTaskRequest {
  reasonCode: PurchaseReceivingCloseReason;
  notes?: string;
}

/**
 * 生产结单后等待仓库确认的既有 settlement 投影。
 * 来源是真实 ProductionSettlement，不是另一套入库任务。
 */
export function getPendingPurchaseReceivingTasks(
  factoryId: string,
  filters?: { purchaseOrderId?: string; orderNumber?: string },
) {
  return get<PurchaseReceivingTask[]>(`/${factoryId}/warehouse/receiving/tasks`, {
    params: {
      purchaseOrderId: filters?.purchaseOrderId || undefined,
      orderNumber: filters?.orderNumber || undefined,
    },
  });
}

export function getPendingWarehouseReceivingTasks(
  factoryId: string,
  filters?: WarehouseReceivingTaskFilters,
) {
  return get<WarehouseReceivingTask[]>(`/${factoryId}/warehouse/receiving/tasks`, {
    params: {
      purchaseOrderId: filters?.purchaseOrderId || undefined,
      orderNumber: filters?.orderNumber || undefined,
      salesOrderId: filters?.salesOrderId || undefined,
      salesOrderNo: filters?.salesOrderNo || undefined,
      sourceType: filters?.sourceType || undefined,
    },
  });
}

export function createCustomerSuppliedReceipt(
  factoryId: string,
  taskId: string,
  request: CreateCustomerSuppliedReceiptRequest,
) {
  return post<CustomerSuppliedReceiptResult>(
    `/${factoryId}/warehouse/receiving/tasks/${taskId}/receipts`,
    request,
  );
}

export function closePurchaseReceivingTask(
  factoryId: string,
  taskId: string,
  request: ClosePurchaseReceivingTaskRequest,
) {
  return post<'CLOSED' | 'COMPLETED'>(
    `/${factoryId}/warehouse/receiving/tasks/${taskId}/close-short`,
    request,
  );
}

/**
 * 采购订单累计已收汇总 (跨页权威值, 替代 list.vue 的 page-local cumulativeForRow).
 * 异常走 axios interceptor (sticky toast).
 */
export function getCumulativeReceived(factoryId: string, orderId: string) {
  return get<CumulativeReceived>(
    `/${factoryId}/purchase/orders/${orderId}/cumulative-received`
  );
}

/**
 * 采购订单分次收货时序明细 (第N次/日期/数量), createdAt 升序.
 * 客户张权: "第一次收了多少第二次收了多少更直观". 空列表 = 暂无收货记录.
 */
export function getOrderReceiveSequence(factoryId: string, orderId: string) {
  return get<ReceiveSequenceEntry[]>(
    `/${factoryId}/purchase/orders/${orderId}/receives`
  );
}

/**
 * 采购入库默认仓 — 后端解析本工厂 PURCHASE_INBOUND_DEFAULT 配置 (未配置回退物流仓 WH-LOG)。
 * 供「新建入库单」预填入库仓库 (防呆 Rule 1)。data 为 null = 工厂缺仓库 seed → 前端回退本地默认逻辑。
 * 权限对齐入库单读取, 实际收货的仓管/采购员均可读 (不像超管专属的 /factory/warehouse-defaults)。
 */
export function getPurchaseInboundDefaultWarehouse(factoryId: string) {
  return get<FactoryWarehouse | null>(
    `/${factoryId}/warehouse/receiving/default-warehouse`
  );
}

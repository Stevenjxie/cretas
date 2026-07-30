import type { RouteLocationRaw } from 'vue-router';
import type { TraceDocument } from '@/types/businessDocumentTrace';

/**
 * 单据追踪: documentType → 中文名 / 前端路由。
 *
 * key 与后端两个 trace service 里 `document("<TYPE>", …)` 的字面量一一对应
 * (`ProductionDocumentTraceService` + `BusinessDocumentTraceService`)。
 *
 * 🔴 没有独立详情页的类型**返回 null**, 不要瞎编一个路径 —— 调用方会明说"无独立详情页",
 * 那比把用户丢到一个 404 或者不相干的列表好。
 */
const LABELS: Record<string, string> = {
  SALES_ORDER: '销售订单',
  PURCHASE_ORDER: '采购订单',
  PURCHASE_RECEIPT: '采购入库',
  MATERIAL_REQUISITION: '物料需求单',
  PRODUCTION_PLAN: '生产计划',
  PRODUCTION_BATCH: '生产批次',
  PRODUCTION_SETTLEMENT: '核对结单',
  FINISHED_GOODS_BATCH: '成品批次',
  SALES_DELIVERY: '销售出库',
  SALES_RETURN: '销售退货',
  PURCHASE_RETURN: '采购退货',
  PURCHASE_INVOICE: '采购发票',
  PAYMENT_REQUEST: '付款申请',
  INTERNAL_TRANSFER: '调拨单',
  TRANSFER_DIFF: '调拨差异',
};

export function traceDocumentLabel(documentType: string): string {
  return LABELS[documentType] || documentType;
}

export function documentTraceTarget(
  document: Pick<TraceDocument, 'documentType' | 'documentId'>,
): RouteLocationRaw | null {
  const id = encodeURIComponent(document.documentId);
  switch (document.documentType) {
    case 'SALES_ORDER': return { path: `/sales/orders/${id}` };
    case 'PURCHASE_ORDER': return { path: `/procurement/orders/${id}` };
    case 'PURCHASE_RECEIPT': return { path: '/procurement/receives', query: { documentId: document.documentId } };
    case 'MATERIAL_REQUISITION': return { path: '/production/material-requisitions', query: { documentId: document.documentId } };
    case 'PRODUCTION_PLAN': return { path: '/production/plans', query: { documentId: document.documentId } };
    case 'PRODUCTION_BATCH': return { path: `/production/batches/${id}` };
    case 'FINISHED_GOODS_BATCH': return { path: `/sales/finished-goods/${id}` };
    case 'SALES_DELIVERY': return { path: '/sales/shipments', query: { documentId: document.documentId } };
    case 'SALES_RETURN': return { path: `/sales/returns/${id}` };
    case 'PURCHASE_RETURN': return { path: '/procurement/returns', query: { documentId: document.documentId } };
    case 'PURCHASE_INVOICE': return { path: '/procurement/invoices', query: { documentId: document.documentId } };
    case 'PAYMENT_REQUEST': return { path: '/procurement/payment-requests', query: { documentId: document.documentId } };
    case 'INTERNAL_TRANSFER': return { path: `/transfer/${id}` };
    // PRODUCTION_SETTLEMENT / TRANSFER_DIFF 没有独立路由 —— 前者在计划归档中心里, 后者在调拨详情页内嵌。
    default: return null;
  }
}

export function traceDirectionLabel(direction?: string | null): string {
  if (direction === 'UPSTREAM') return '上游来源';
  if (direction === 'EXECUTION') return '执行环节';
  if (direction === 'DOWNSTREAM') return '结算与出库';
  return '关联单据';
}

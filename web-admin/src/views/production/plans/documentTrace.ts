import type { RouteLocationRaw } from 'vue-router';
import type { ProductionTraceDocument } from '@/types/productionDocumentTrace';

const LABELS: Record<string, string> = {
  SALES_ORDER: '销售订单',
  PURCHASE_ORDER: '采购订单',
  PURCHASE_RECEIPT: '采购入库',
  MATERIAL_REQUISITION: '物料需求单',
  PRODUCTION_BATCH: '生产批次',
  PRODUCTION_SETTLEMENT: '核对结单',
  FINISHED_GOODS_BATCH: '成品批次',
  SALES_DELIVERY: '销售出库',
};

export function traceDocumentLabel(documentType: string): string {
  return LABELS[documentType] || documentType;
}

export function documentTraceTarget(
  document: Pick<ProductionTraceDocument, 'documentType' | 'documentId'>,
): RouteLocationRaw | null {
  const id = encodeURIComponent(document.documentId);
  switch (document.documentType) {
    case 'SALES_ORDER': return { path: `/sales/orders/${id}` };
    case 'PURCHASE_ORDER': return { path: `/procurement/orders/${id}` };
    case 'PURCHASE_RECEIPT': return { path: '/procurement/receives', query: { documentId: document.documentId } };
    case 'MATERIAL_REQUISITION': return { path: '/production/material-requisitions', query: { documentId: document.documentId } };
    case 'PRODUCTION_BATCH': return { path: `/production/batches/${id}` };
    case 'FINISHED_GOODS_BATCH': return { path: `/sales/finished-goods/${id}` };
    case 'SALES_DELIVERY': return { path: '/sales/shipments', query: { documentId: document.documentId } };
    default: return null;
  }
}

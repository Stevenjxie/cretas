/**
 * 单据追踪 API —— 销售订单 / 采购订单 / 调拨单。
 *
 * 生产计划那条在 `api/productionPlan.ts#getProductionDocumentTrace` (路径/响应形状不同, 不合并)。
 */
import { get } from './request'
import type { BusinessDocumentTrace } from '@/types/businessDocumentTrace'

export function getSalesOrderDocumentTrace(factoryId: string, orderId: string) {
  return get<BusinessDocumentTrace>(`/${factoryId}/sales/orders/${orderId}/document-trace`)
}

export function getPurchaseOrderDocumentTrace(factoryId: string, orderId: string) {
  return get<BusinessDocumentTrace>(`/${factoryId}/purchase/orders/${orderId}/document-trace`)
}

export function getTransferDocumentTrace(factoryId: string, transferId: string) {
  return get<BusinessDocumentTrace>(`/${factoryId}/transfers/${transferId}/document-trace`)
}

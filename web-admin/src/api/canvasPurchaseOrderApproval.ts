/**
 * Canvas 采购订单审批规则 API client.
 *
 * Backend: {@link CanvasPurchaseOrderApprovalController} @ /api/mobile/{factoryId}/canvas-purchase-order-approval
 *
 * Endpoints:
 *   GET    /                   列出本工厂规则 (含 disabled)
 *   GET    /{id}               按 id 查询
 *   POST   /                   创建规则
 *   PUT    /{id}               更新 (PATCH semantics, Map body)
 *   DELETE /{id}               软删除
 *
 * @since 2026-05-22 (Canvas P3 batch 2)
 */
import request from './request'

export interface PurchaseOrderApprovalRule {
  id: string
  factoryId: string
  ruleName: string
  priceVarianceThreshold: number | string
  amountThreshold: number | string | null
  enabled: boolean
  version?: number
  createdAt?: string
  updatedAt?: string
  deletedAt?: string | null
}

const base = (factoryId: string) => `/${factoryId}/canvas-purchase-order-approval`

export const purchaseOrderApprovalApi = {
  list: (factoryId: string) =>
    request.get<PurchaseOrderApprovalRule[]>(base(factoryId)),

  get: (factoryId: string, id: string) =>
    request.get<PurchaseOrderApprovalRule>(`${base(factoryId)}/${id}`),

  create: (factoryId: string, body: Partial<PurchaseOrderApprovalRule>) =>
    request.post<PurchaseOrderApprovalRule>(base(factoryId), body),

  update: (factoryId: string, id: string, body: Partial<PurchaseOrderApprovalRule>) =>
    request.put<PurchaseOrderApprovalRule>(`${base(factoryId)}/${id}`, body),

  delete: (factoryId: string, id: string) =>
    request.delete<void>(`${base(factoryId)}/${id}`),
}

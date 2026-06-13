/**
 * Production Plan API
 * Import/Export and reference data endpoints for production plans
 */
import request from './request'
import { get, post } from './request'

/** Download the Excel import template (returns Blob) */
export function downloadImportTemplate(factoryId: string) {
  return request.get(`/${factoryId}/production-plans/import-template`, {
    responseType: 'blob',
  })
}

/** Import production plans from an Excel file */
export function importProductionPlans(factoryId: string, formData: FormData) {
  return post<{
    totalCount: number
    successCount: number
    failureCount: number
    failureDetails?: Array<{ rowNumber: number; reason: string }>
  }>(`/${factoryId}/production-plans/import`, formData)
}

/** Export production plans as Excel (returns Blob) */
export function exportProductionPlans(factoryId: string, params?: Record<string, string>) {
  return request.get(`/${factoryId}/production-plans/export`, {
    params,
    responseType: 'blob',
  })
}

/** Get all production lines for a factory */
export function getProductionLines(factoryId: string, status?: string) {
  return get<Array<{ id: string; name: string; status: string; [key: string]: unknown }>>(`/${factoryId}/scheduling/production-lines`, {
    params: status ? { status } : undefined
  })
}

/** Get supervisors (workshop supervisors) for a factory */
export function getSupervisors(factoryId: string) {
  return get<Array<{ id: number; username: string; realName?: string; [key: string]: unknown }>>(`/${factoryId}/users`, {
    params: { role: 'WORKSHOP_SUPERVISOR' },
  })
}

/**
 * 工厂级"免工序报工默认值" (Fable 审计修复 — 多租户安全, 问题1).
 * 新建计划对话框据此初始化"免工序报工"开关: F006=true (默认两点), 其他工厂=false (默认逐道)。
 */
export function getReportModeDefault(factoryId: string) {
  return get<boolean>(`/${factoryId}/production-plans/report-mode-default`)
}

export interface MaterialAdvisoryItem {
  materialTypeId: string
  materialName: string
  requiredQuantity: number | null
  availableQuantity: number | null
  shortageQuantity: number | null
  unit: string | null
  message: string
}

export interface ProductionPlanMaterialAdvisory {
  planId: string
  planNumber: string
  hasWarning: boolean
  message: string
  warnings: MaterialAdvisoryItem[]
}

export function getMaterialAdvisory(factoryId: string, planId: string) {
  return get<ProductionPlanMaterialAdvisory>(`/${factoryId}/production-plans/${planId}/material-advisory`)
}

export interface ProductionSettlementStatus {
  settlementId: string
  productionPlanId: string
  planNumber: string
  status: string
  plannedQuantity: number
  actualFinishedQuantity: number
  actualSemiFinishedQuantity: number
  quantityUnit?: string | null
  postingStatus: string
  postingMessage?: string | null
  warehouseReceivedQuantity?: number | null
  warehouseVarianceQuantity?: number | null
  finishedGoodsBatchId?: string | null
  transitLedgerId?: string | null
  warnings?: string[]
}

export interface ProductionWarehouseReceiptRequest {
  idempotencyKey: string
  receivedQuantity: number
  quantityUnit?: string | null
  varianceReason?: string | null
  responsibilitySide?: string | null
  varianceNote?: string | null
}

export interface ProductionWarehouseReceiptResponse {
  settlementId: string
  productionPlanId: string
  planNumber: string
  productionReportedQuantity: number
  warehouseReceivedQuantity: number
  varianceQuantity: number
  toleranceQuantity: number
  quantityUnit: string
  postingStatus: string
  finishedGoodsBatchId?: string | null
  transitLedgerId?: string | null
  message?: string | null
  warnings?: string[]
}

export function getProductionSettlement(factoryId: string, planId: string) {
  return get<ProductionSettlementStatus>(`/${factoryId}/production-plans/${planId}/settlement`)
}

export function confirmProductionWarehouseReceipt(
  factoryId: string,
  planId: string,
  data: ProductionWarehouseReceiptRequest,
) {
  return post<ProductionWarehouseReceiptResponse>(`/${factoryId}/production-plans/${planId}/warehouse-receipt`, data)
}

/** SP2: WIP 半成品可用库存列表 (GET /wip/available) */
export interface WipInventoryItem {
  id: number
  intermediateBatchNo: string
  productTypeId: string
  producedQuantity: number
  consumedQuantity: number
  availableQuantity: number
  unit: string | null
  status: string
  accumulatedCost: number | null
  unitCost: number | null
  batchId: number | null
  createdAt: string | null
}

export function listAvailableWip(factoryId: string) {
  return get<WipInventoryItem[]>(`/${factoryId}/wip/available`)
}

/** SP2: 创建二次加工计划 (POST /processing/secondary-plan) */
export interface CreateSecondaryPlanRequest {
  wipId: number
  quantity: number
  productTypeId: string
  plannedDate?: string
}

export function createSecondaryPlan(factoryId: string, data: CreateSecondaryPlanRequest) {
  return post<Record<string, unknown>>(`/${factoryId}/processing/secondary-plan`, data)
}

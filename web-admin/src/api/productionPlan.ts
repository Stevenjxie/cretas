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

/** SP2: WIP 半成品可用库存列表 (GET /processing/wip/available) */
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
  return get<WipInventoryItem[]>(`/${factoryId}/processing/wip/available`)
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

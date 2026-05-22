/**
 * Food Safety Hub API client — Canvas Phase A Tab 2 (Food Safety Hub).
 *
 * Backend: CanvasFoodSafetyController @ /api/mobile/{factoryId}/canvas-food-safety
 *
 * 5 wrapped entities (Sprint 8 P3 Phase A):
 *   - HaccpCheckpoint (full CRUD)
 *   - HaccpMonitoringRecord (list)
 *   - AdditiveLimit (read-only GB 2760)
 *   - RecallEvent (full CRUD with status workflow)
 *   - RecallAction (list per event)
 *
 * 3 placeholder sub-tabs (Sprint 9 entities not yet on main):
 *   - Food Sample / Nutrition Label / Supplier Qualification / Cold Chain / SSOP
 *
 * @since 2026-05-21 (Canvas Phase A subagent #2)
 */
import request from './request'

// ==================== Types ====================

export type HazardType = 'BIOLOGICAL' | 'CHEMICAL' | 'PHYSICAL'
export type RecallStatus =
  | 'INVESTIGATING'
  | 'NOTIFYING'
  | 'FROZEN'
  | 'REPORTED'
  | 'COMPLETED'

export const HazardTypeLabels: Record<HazardType, string> = {
  BIOLOGICAL: '生物性危害',
  CHEMICAL: '化学性危害',
  PHYSICAL: '物理性危害',
}

export const RecallStatusLabels: Record<RecallStatus, string> = {
  INVESTIGATING: '调查中',
  NOTIFYING: '客户通知中',
  FROZEN: '库存冻结',
  REPORTED: '监管已上报',
  COMPLETED: '已闭环',
}

export const RecallStatusTagType: Record<RecallStatus, string> = {
  INVESTIGATING: 'warning',
  NOTIFYING: 'warning',
  FROZEN: 'danger',
  REPORTED: 'info',
  COMPLETED: 'success',
}

export interface HaccpCheckpoint {
  id?: number
  factoryId?: string
  checkpointCode: string
  name: string
  hazardType: HazardType
  description?: string
  criticalLimitMin: number | string
  criticalLimitMax: number | string
  unit: string
  monitoringProcedure?: string
  correctiveAction?: string
  verificationProcedure?: string
  recordKeeping?: string
  active: boolean
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface HaccpMonitoringRecord {
  id: number
  factoryId: string
  checkpointId: number
  batchNumber: string
  monitoringTime?: string
  measuredValue: number | string
  operatorUserId: number
  isDeviation: boolean
  createdAt?: string
}

export interface AdditiveLimit {
  id: number
  additiveName: string
  additiveCode: string
  foodCategory: string
  maxLimit: number | string
  unit: string
  regulationRef: string
  active: boolean
}

export interface RecallEvent {
  id?: number
  factoryId?: string
  eventCode: string
  triggerReason: string
  affectedProductCategory: string
  triggerTime?: string
  triggeredByUserId: number
  status: RecallStatus
  completedAt?: string | null
  estimatedLoss?: number | string | null
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface RecallAction {
  id: number
  recallEventId: number
  createdAt?: string
}

export interface FoodSafetySummary {
  haccpCheckpointsActive: number
  haccpDeviations: number
  recallsOpen: number
  additiveLimitsTotal: number
  foodSamplePending: number
  nutritionLabelPending: number
  supplierQualPending: number
}

interface ApiResponse<T> {
  success: boolean
  data: T
  message?: string
  code?: string | number
}

// ==================== API methods ====================

const base = (factoryId: string) => `/${factoryId}/canvas-food-safety`

/** Summary counts for all 5 wrapped entities + 3 placeholder counters. */
export const getSummary = (factoryId: string) =>
  request.get<ApiResponse<FoodSafetySummary>>(`${base(factoryId)}/summary`)

// ----- HACCP Checkpoints -----

export const listCheckpoints = (factoryId: string, activeOnly?: boolean) =>
  request.get<ApiResponse<HaccpCheckpoint[]>>(`${base(factoryId)}/haccp/checkpoints`, {
    params: activeOnly !== undefined ? { activeOnly } : {},
  })

export const createCheckpoint = (factoryId: string, payload: HaccpCheckpoint) =>
  request.post<ApiResponse<HaccpCheckpoint>>(
    `${base(factoryId)}/haccp/checkpoints`,
    payload,
  )

export const updateCheckpoint = (
  factoryId: string,
  id: number,
  payload: Partial<HaccpCheckpoint>,
) =>
  request.put<ApiResponse<HaccpCheckpoint>>(
    `${base(factoryId)}/haccp/checkpoints/${id}`,
    payload,
  )

export const deleteCheckpoint = (factoryId: string, id: number) =>
  request.delete<ApiResponse<void>>(`${base(factoryId)}/haccp/checkpoints/${id}`)

// ----- HACCP Monitoring (read-only) -----

export const listMonitoring = (
  factoryId: string,
  params: { batchNumber?: string; deviationsOnly?: boolean } = {},
) =>
  request.get<ApiResponse<HaccpMonitoringRecord[]>>(`${base(factoryId)}/haccp/monitoring`, {
    params,
  })

// ----- Additive Limits (read-only) -----

export const listAdditiveLimits = (factoryId: string, foodCategory?: string) =>
  request.get<ApiResponse<AdditiveLimit[]>>(`${base(factoryId)}/additive-limits`, {
    params: foodCategory ? { foodCategory } : {},
  })

// ----- Recall Events -----

export const listRecalls = (factoryId: string) =>
  request.get<ApiResponse<RecallEvent[]>>(`${base(factoryId)}/recalls`)

export const createRecall = (factoryId: string, payload: RecallEvent) =>
  request.post<ApiResponse<RecallEvent>>(`${base(factoryId)}/recalls`, payload)

export const updateRecall = (
  factoryId: string,
  id: number,
  payload: Partial<RecallEvent>,
) =>
  request.put<ApiResponse<RecallEvent>>(`${base(factoryId)}/recalls/${id}`, payload)

// ----- Recall Actions -----

export const listRecallActions = (factoryId: string, eventId: number) =>
  request.get<ApiResponse<RecallAction[]>>(
    `${base(factoryId)}/recalls/${eventId}/actions`,
  )

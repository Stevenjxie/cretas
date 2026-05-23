/**
 * Canvas-SupplierAdmission API client (Phase BCP3 — 2026-05-22).
 *
 * Backend: CanvasSupplierAdmissionController @ /api/mobile/{factoryId}/canvas-supplier-admission
 *
 * Endpoints:
 *   GET    /                          列出工厂供应商 (可选 ?admissionStatus=)
 *   GET    /{supplierId}              查询单个 (准入视角)
 *   POST   /{supplierId}/review       提交准入审核
 *   PUT    /{supplierId}              修改准入字段 (PATCH + AUD-4 P1)
 *   DELETE /{supplierId}              暂停准入 (SUSPENDED + isActive=false)
 */
import request from './request'

export type AdmissionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED'

export const ADMISSION_STATUS_LABELS: Record<AdmissionStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已准入',
  REJECTED: '已拒绝',
  SUSPENDED: '已暂停',
}

export const ADMISSION_STATUS_LIST: AdmissionStatus[] = [
  'PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED',
]

export interface SupplierAdmissionView {
  id: string
  supplierCode?: string
  name?: string
  contactPerson?: string
  contactPhone?: string
  businessLicense?: string
  qualityCertificates?: string
  creditLevel?: string
  rating?: number
  ratingNotes?: string
  isActive?: boolean
  admissionStatus?: AdmissionStatus
  admissionReviewedAt?: string
  admissionReviewerId?: number
  version?: number
  createdAt?: string
  updatedAt?: string
}

export interface ReviewPayload {
  admissionStatus: AdmissionStatus
  notes?: string
  reviewerId?: number
}

const base = (factoryId: string) => `/${factoryId}/canvas-supplier-admission`

export const canvasSupplierAdmissionApi = {
  list(factoryId: string, admissionStatus?: AdmissionStatus) {
    return request.get<SupplierAdmissionView[]>(base(factoryId), {
      params: admissionStatus ? { admissionStatus } : undefined,
    })
  },

  getById(factoryId: string, supplierId: string) {
    return request.get<SupplierAdmissionView>(`${base(factoryId)}/${supplierId}`)
  },

  review(factoryId: string, supplierId: string, payload: ReviewPayload) {
    return request.post<SupplierAdmissionView>(
      `${base(factoryId)}/${supplierId}/review`, payload,
    )
  },

  update(factoryId: string, supplierId: string,
         payload: Partial<SupplierAdmissionView> & { version?: number }) {
    return request.put<SupplierAdmissionView>(`${base(factoryId)}/${supplierId}`, payload)
  },

  suspend(factoryId: string, supplierId: string) {
    return request.delete<void>(`${base(factoryId)}/${supplierId}`)
  },
}

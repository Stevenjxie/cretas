/**
 * Canvas-HrInsurance API client (Phase BCP3 — 2026-05-22).
 *
 * Backend: CanvasHrInsuranceController @ /api/mobile/{factoryId}/canvas-hr-insurance
 *
 * Endpoints:
 *   GET    /                列出工厂全部费率配置 (倒序)
 *   GET    /{id}            查询单个
 *   GET    /active          查询当前生效 (status=ACTIVE)
 *   POST   /                创建 (旧 ACTIVE 自动 ARCHIVED)
 *   PUT    /{id}            修改 (PATCH + AUD-4 P1 optimistic lock)
 *   DELETE /{id}            软删除 (不可删 ACTIVE)
 */
import request from './request'

export type InsuranceStatus = 'ACTIVE' | 'ARCHIVED'

export interface HrInsuranceConfig {
  id: string
  factoryId: string
  employeePensionRate: string | number
  employerPensionRate: string | number
  employeeMedicalRate: string | number
  employerMedicalRate: string | number
  employeeUnemploymentRate: string | number
  employerUnemploymentRate: string | number
  employeeProvidentFundRate: string | number
  employerProvidentFundRate: string | number
  baseSalaryLowerBound?: string | number | null
  baseSalaryUpperBound?: string | number | null
  effectiveFrom: string
  status: InsuranceStatus
  remark?: string
  createdBy?: number
  optLockVersion?: number
  createdAt?: string
  updatedAt?: string
}

const base = (factoryId: string) => `/${factoryId}/canvas-hr-insurance`

export const canvasHrInsuranceApi = {
  list(factoryId: string) {
    return request.get<HrInsuranceConfig[]>(base(factoryId))
  },

  getById(factoryId: string, id: string) {
    return request.get<HrInsuranceConfig>(`${base(factoryId)}/${id}`)
  },

  getActive(factoryId: string) {
    return request.get<HrInsuranceConfig>(`${base(factoryId)}/active`)
  },

  create(factoryId: string, payload: Partial<HrInsuranceConfig>) {
    return request.post<HrInsuranceConfig>(base(factoryId), payload)
  },

  update(factoryId: string, id: string, payload: Partial<HrInsuranceConfig> & { version?: number }) {
    return request.put<HrInsuranceConfig>(`${base(factoryId)}/${id}`, payload)
  },

  remove(factoryId: string, id: string) {
    return request.delete<void>(`${base(factoryId)}/${id}`)
  },
}

/**
 * Factory Config Hub API client — Canvas Phase B Tab 1 (工厂配置中心).
 *
 * Backend: CanvasFactoryConfigController @ /api/mobile/{factoryId}/canvas-factory-config
 *
 * 6 wrapped sub-modules:
 *   1. SchedulingConfig (排班权重 / 临时工因子 / 自适应学习)
 *   2. FactoryTempWorker (临时工记录)
 *   3. HrInsuranceConfig (五险一金费率, 历史版本)
 *   4. WagePolicy (工资模式 PIECE_RATE/HOURLY/MIXED)
 *   5. EncodingRule (业务单据编号规则)
 *   6. FactorySettings (工厂总设置 AI/通知/工时/...)
 *
 * @since 2026-05-22 (Canvas Phase B)
 */
import request from './request'

// ==================== Types ====================

export interface FactoryConfigOverview {
  schedulingConfigured: boolean
  tempWorkerCount: number
  insuranceActive: boolean
  wagePolicyCount: number
  encodingRuleCount: number
  factorySettingsConfigured: boolean
}

export interface SchedulingConfig {
  id?: number
  factoryId: string
  enabled: boolean
  diversityEnabled: boolean
  linucbWeight: number
  fairnessWeight: number
  skillMaintenanceWeight: number
  repetitionWeight: number
  skillDecayDays: number
  fairnessPeriodDays: number
  repetitionDays: number
  maxConsecutiveDays: number
  tempWorkerLinucbFactor: number
  tempWorkerFairnessFactor: number
  tempWorkerSkillDecayDays: number
  tempWorkerThresholdDays: number
  tempWorkerMinAssignments: number
  adaptiveLearningEnabled: boolean
  learningRate: number
  efficiencyTarget: number
  diversityTarget: number
  version: number
  createdAt?: string
  updatedAt?: string
}

export interface TempWorker {
  id?: number
  factoryId: string
  workerId: number
  isTempWorker: boolean
  hireDate: string
  expectedEndDate?: string | null
  convertedToPermanent: boolean
  conversionDate?: string | null
  initialSkillLevel: number
  currentSkillLevel: number
  skillGrowthRate: number
  totalAssignments: number
  avgEfficiency: number
  reliabilityScore: number
  version: number
  daysEmployed?: number
}

export interface InsuranceConfig {
  id?: string
  factoryId: string
  status: 'ACTIVE' | 'ARCHIVED'
  effectiveFrom: string
  employeePensionRate: number
  employerPensionRate: number
  employeeMedicalRate: number
  employerMedicalRate: number
  employeeUnemploymentRate: number
  employerUnemploymentRate: number
  employeeProvidentFundRate: number
  employerProvidentFundRate: number
  baseSalaryLowerBound?: number | null
  baseSalaryUpperBound?: number | null
  remark?: string
  version: number
  createdAt?: string
}

export interface InsuranceResponse {
  history: InsuranceConfig[]
  active?: InsuranceConfig
}

export type WageMode = 'PIECE_RATE' | 'HOURLY' | 'MIXED'

export const WAGE_MODE_LABELS: Record<WageMode, string> = {
  PIECE_RATE: '计件',
  HOURLY: '计时',
  MIXED: '混合',
}

export interface WagePolicy {
  id?: number
  factoryId: string
  employeeId?: number | null
  mode: WageMode
  mixedFormulaHint?: string
  isActive: boolean
  notes?: string
  version: number
}

export type ResetCycle = 'DAILY' | 'MONTHLY' | 'YEARLY' | 'NEVER'

export const RESET_CYCLE_LABELS: Record<ResetCycle, string> = {
  DAILY: '每日',
  MONTHLY: '每月',
  YEARLY: '每年',
  NEVER: '不重置',
}

export interface EncodingRule {
  id?: string
  factoryId: string
  entityType: string
  ruleName: string
  ruleDescription?: string
  encodingPattern: string
  prefix?: string
  dateFormat?: string
  sequenceLength: number
  resetCycle: ResetCycle
  currentSequence: number
  lastResetDate?: string | null
  separator: string
  includeFactoryCode: boolean
  enabled: boolean
  version: number          // business rule version
  lockVersion: number      // AUD-4 optimistic lock
}

export interface FactorySettings {
  id?: number
  factoryId: string
  factoryName?: string
  factoryAddress?: string
  contactPhone?: string
  contactEmail?: string
  workingHours?: number
  language?: string
  timezone?: string
  currency?: string
  dateFormat?: string
  aiSettings?: string
  aiWeeklyQuota?: number
  notificationSettings?: string
  enableQrCode?: boolean
  enableBatchManagement?: boolean
  enableQualityCheck?: boolean
  enableCostCalculation?: boolean
  enableEquipmentManagement?: boolean
  enableAttendance?: boolean
  skipProcessReportingDefault?: boolean
  requireRequisitionBeforeReport?: boolean
  allowSelfRegistration?: boolean
  requireAdminApproval?: boolean
  defaultUserRole?: string
  lastModifiedAt?: string
  version: number
}

// ==================== Endpoints ====================

const base = (factoryId: string) => `/${factoryId}/canvas-factory-config`

export function getOverview(factoryId: string) {
  return request.get<any, { success: boolean; data: FactoryConfigOverview }>(
    `${base(factoryId)}/overview`,
  )
}

// Scheduling
export function getScheduling(factoryId: string) {
  return request.get<any, { success: boolean; data: SchedulingConfig }>(
    `${base(factoryId)}/scheduling`,
  )
}

export function updateScheduling(factoryId: string, body: Partial<SchedulingConfig>) {
  return request.put<any, { success: boolean; data: SchedulingConfig }>(
    `${base(factoryId)}/scheduling`, body,
  )
}

// Temp Workers
export function listTempWorkers(factoryId: string) {
  return request.get<any, { success: boolean; data: TempWorker[] }>(
    `${base(factoryId)}/temp-workers`,
  )
}

export function createTempWorker(factoryId: string, body: Partial<TempWorker>) {
  return request.post<any, { success: boolean; data: TempWorker }>(
    `${base(factoryId)}/temp-workers`, body,
  )
}

export function updateTempWorker(factoryId: string, id: number, body: Partial<TempWorker>) {
  return request.put<any, { success: boolean; data: TempWorker }>(
    `${base(factoryId)}/temp-workers/${id}`, body,
  )
}

// Insurance
export function listInsurance(factoryId: string) {
  return request.get<any, { success: boolean; data: InsuranceResponse }>(
    `${base(factoryId)}/insurance`,
  )
}

export function createInsurance(factoryId: string, body: Partial<InsuranceConfig>) {
  return request.post<any, { success: boolean; data: InsuranceConfig }>(
    `${base(factoryId)}/insurance`, body,
  )
}

// Wage Policies
export function listWagePolicies(factoryId: string) {
  return request.get<any, { success: boolean; data: WagePolicy[] }>(
    `${base(factoryId)}/wage-policies`,
  )
}

export function createWagePolicy(factoryId: string, body: Partial<WagePolicy>) {
  return request.post<any, { success: boolean; data: WagePolicy }>(
    `${base(factoryId)}/wage-policies`, body,
  )
}

export function updateWagePolicy(factoryId: string, id: number, body: Partial<WagePolicy>) {
  return request.put<any, { success: boolean; data: WagePolicy }>(
    `${base(factoryId)}/wage-policies/${id}`, body,
  )
}

// Encoding Rules
export function listEncodingRules(factoryId: string) {
  return request.get<any, { success: boolean; data: EncodingRule[] }>(
    `${base(factoryId)}/encoding-rules`,
  )
}

export function createEncodingRule(factoryId: string, body: Partial<EncodingRule>) {
  return request.post<any, { success: boolean; data: EncodingRule }>(
    `${base(factoryId)}/encoding-rules`, body,
  )
}

export function updateEncodingRule(factoryId: string, id: string, body: Partial<EncodingRule>) {
  return request.put<any, { success: boolean; data: EncodingRule }>(
    `${base(factoryId)}/encoding-rules/${id}`, body,
  )
}

// Factory Settings
export function getSettings(factoryId: string) {
  return request.get<any, { success: boolean; data: FactorySettings }>(
    `${base(factoryId)}/settings`,
  )
}

export function updateSettings(factoryId: string, body: Partial<FactorySettings>) {
  return request.put<any, { success: boolean; data: FactorySettings }>(
    `${base(factoryId)}/settings`, body,
  )
}

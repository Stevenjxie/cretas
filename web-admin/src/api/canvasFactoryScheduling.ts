/**
 * Canvas 工厂排班配置 API client.
 *
 * Backend: {@link CanvasFactorySchedulingController} @ /api/mobile/{factoryId}/canvas-factory-scheduling
 *
 * Endpoints:
 *   GET    /                   列出工厂排班配置 (per-factory 唯一, 空或单条)
 *   GET    /{id}               按 id 查询
 *   POST   /                   创建配置
 *   PUT    /{id}               更新 (PATCH semantics, Map body)
 *   DELETE /{id}               软删除
 *
 * @since 2026-05-22 (Canvas P3 batch 2)
 */
import request from './request'

export interface FactorySchedulingConfig {
  id: number
  factoryId: string
  enabled?: boolean
  diversityEnabled?: boolean

  // 权重参数
  linucbWeight?: number
  fairnessWeight?: number
  skillMaintenanceWeight?: number
  repetitionWeight?: number

  // 时间参数
  skillDecayDays?: number
  fairnessPeriodDays?: number
  repetitionDays?: number
  maxConsecutiveDays?: number

  // 临时工配置
  tempWorkerLinucbFactor?: number
  tempWorkerFairnessFactor?: number
  tempWorkerSkillDecayDays?: number
  tempWorkerThresholdDays?: number
  tempWorkerMinAssignments?: number

  // SKU 复杂度
  skuComplexityWeight?: number
  highComplexitySkillThreshold?: number
  lowComplexityForTraining?: boolean

  // 自适应学习
  adaptiveLearningEnabled?: boolean
  learningRate?: number
  minSamplesForAdaptation?: number
  efficiencyTarget?: number
  diversityTarget?: number

  // 异常检测
  anomalyDetectionEnabled?: boolean
  efficiencyAnomalyThreshold?: number
  anomalyCountForCalibration?: number

  // APS 排产策略权重
  earliestDeadlineWeight?: number
  minChangeoverWeight?: number
  capacityMatchWeight?: number
  shortestProcessWeight?: number
  materialReadyWeight?: number
  urgencyFirstWeight?: number

  // 审计字段
  createdAt?: string
  updatedAt?: string
  lastAdaptationAt?: string
  adaptationCount?: number
  version?: number
  deletedAt?: string | null
}

const base = (factoryId: string) => `/${factoryId}/canvas-factory-scheduling`

export const factorySchedulingApi = {
  list: (factoryId: string) =>
    request.get<FactorySchedulingConfig[]>(base(factoryId)),

  get: (factoryId: string, id: number) =>
    request.get<FactorySchedulingConfig>(`${base(factoryId)}/${id}`),

  create: (factoryId: string, body: Partial<FactorySchedulingConfig>) =>
    request.post<FactorySchedulingConfig>(base(factoryId), body),

  update: (factoryId: string, id: number, body: Partial<FactorySchedulingConfig>) =>
    request.put<FactorySchedulingConfig>(`${base(factoryId)}/${id}`, body),

  delete: (factoryId: string, id: number) =>
    request.delete<void>(`${base(factoryId)}/${id}`),
}

/**
 * 字段分组 — 用于 Vue 编辑器 collapse 折叠面板.
 */
export const FIELD_GROUPS = {
  basic: {
    title: '基础配置',
    fields: ['enabled', 'diversityEnabled'] as const,
  },
  weights: {
    title: '调度权重 (LinUCB / 公平性 / 技能维护 / 重复)',
    fields: [
      'linucbWeight',
      'fairnessWeight',
      'skillMaintenanceWeight',
      'repetitionWeight',
    ] as const,
  },
  time: {
    title: '时间参数',
    fields: [
      'skillDecayDays',
      'fairnessPeriodDays',
      'repetitionDays',
      'maxConsecutiveDays',
    ] as const,
  },
  tempWorker: {
    title: '临时工策略',
    fields: [
      'tempWorkerLinucbFactor',
      'tempWorkerFairnessFactor',
      'tempWorkerSkillDecayDays',
      'tempWorkerThresholdDays',
      'tempWorkerMinAssignments',
    ] as const,
  },
  skuComplexity: {
    title: 'SKU 复杂度',
    fields: [
      'skuComplexityWeight',
      'highComplexitySkillThreshold',
      'lowComplexityForTraining',
    ] as const,
  },
  adaptive: {
    title: '自适应学习',
    fields: [
      'adaptiveLearningEnabled',
      'learningRate',
      'minSamplesForAdaptation',
      'efficiencyTarget',
      'diversityTarget',
    ] as const,
  },
  anomaly: {
    title: '异常检测',
    fields: [
      'anomalyDetectionEnabled',
      'efficiencyAnomalyThreshold',
      'anomalyCountForCalibration',
    ] as const,
  },
  apsStrategy: {
    title: 'APS 排产策略权重',
    fields: [
      'earliestDeadlineWeight',
      'minChangeoverWeight',
      'capacityMatchWeight',
      'shortestProcessWeight',
      'materialReadyWeight',
      'urgencyFirstWeight',
    ] as const,
  },
}

export const FIELD_LABELS: Record<keyof FactorySchedulingConfig, string> = {
  id: 'ID',
  factoryId: '工厂',
  enabled: '启用动态配置',
  diversityEnabled: '启用多样性调整',
  linucbWeight: 'LinUCB 分数权重',
  fairnessWeight: '公平性加分权重',
  skillMaintenanceWeight: '技能维护加分权重',
  repetitionWeight: '重复惩罚权重',
  skillDecayDays: '技能遗忘判定天数',
  fairnessPeriodDays: '公平性计算周期天数',
  repetitionDays: '重复判定天数',
  maxConsecutiveDays: '最大同工序连续天数',
  tempWorkerLinucbFactor: '临时工 LinUCB 权重因子',
  tempWorkerFairnessFactor: '临时工公平性权重因子',
  tempWorkerSkillDecayDays: '临时工技能遗忘天数',
  tempWorkerThresholdDays: '临时工判定天数',
  tempWorkerMinAssignments: '临时工最低分配数',
  skuComplexityWeight: 'SKU 复杂度权重',
  highComplexitySkillThreshold: '高复杂度技能阈值',
  lowComplexityForTraining: '低复杂度优先给新人',
  adaptiveLearningEnabled: '启用自适应学习',
  learningRate: '学习率',
  minSamplesForAdaptation: '最小样本数',
  efficiencyTarget: '效率提升目标',
  diversityTarget: '多样性目标',
  anomalyDetectionEnabled: '启用异常检测',
  efficiencyAnomalyThreshold: '效率异常阈值',
  anomalyCountForCalibration: '触发校准异常次数',
  earliestDeadlineWeight: '最早交期优先权重',
  minChangeoverWeight: '最小换型时间权重',
  capacityMatchWeight: '产能匹配权重',
  shortestProcessWeight: '最短工序优先权重',
  materialReadyWeight: '物料齐套优先权重',
  urgencyFirstWeight: '紧急订单优先权重',
  createdAt: '创建时间',
  updatedAt: '更新时间',
  lastAdaptationAt: '最后调整时间',
  adaptationCount: '调整次数',
  version: '版本号',
  deletedAt: '删除时间',
}

/**
 * 判断字段是否为权重 (0.0-1.0).
 */
export function isWeightField(field: keyof FactorySchedulingConfig): boolean {
  const weightFields: (keyof FactorySchedulingConfig)[] = [
    'linucbWeight',
    'fairnessWeight',
    'skillMaintenanceWeight',
    'repetitionWeight',
    'skuComplexityWeight',
    'learningRate',
    'efficiencyTarget',
    'diversityTarget',
    'efficiencyAnomalyThreshold',
    'earliestDeadlineWeight',
    'minChangeoverWeight',
    'capacityMatchWeight',
    'shortestProcessWeight',
    'materialReadyWeight',
    'urgencyFirstWeight',
  ]
  return weightFields.includes(field)
}

/**
 * Canvas 指标中心 API client.
 *
 * Backend: {@link CanvasIndicatorsController} @ /api/mobile/{factoryId}/canvas-indicators
 *
 * Endpoints:
 *   GET    /                                列出指标 (可选 ?category=)
 *   GET    /categories                      列出全部分类
 *   GET    /{id}                            指标详情 (含 thresholds + computations + 最新 version)
 *   POST   /                                创建指标
 *   PUT    /{id}                            更新指标 (PATCH semantics)
 *   DELETE /{id}                            软删除 (is_active=false)
 *   GET    /{id}/versions                   版本历史 (分页)
 *   GET    /{id}/measurements               趋势数据 (时间窗)
 *   POST   /{id}/test-evaluate              公式 dry-run (SpEL sandbox)
 *   POST   /{id}/recompute                  强制重算并落 snapshot
 *   GET    /{id}/lineage                    指标依赖图
 *   GET    /tree                            指标 DAG (vue-flow nodes + edges)
 *   GET    /batch-lineage/{batchType}/{batchId}   批次溯源
 *
 * @since 2026-05-21 (Canvas Phase A subagent #3)
 */
import request from './request'

export type IndicatorCategory =
  | 'FACTORY'
  | 'RESTAURANT'
  | 'QUALITY'
  | 'FINANCE'
  | 'INVENTORY'
  | 'SALES'
  | 'FOOD_SAFETY'
  | 'AI'

export const INDICATOR_CATEGORY_LABELS: Record<IndicatorCategory, string> = {
  FACTORY: '工厂',
  RESTAURANT: '餐饮',
  QUALITY: '质量',
  FINANCE: '财务',
  INVENTORY: '库存',
  SALES: '销售',
  FOOD_SAFETY: '食品安全',
  AI: 'AI',
}

export const INDICATOR_CATEGORIES: IndicatorCategory[] = [
  'FACTORY', 'RESTAURANT', 'QUALITY', 'FINANCE',
  'INVENTORY', 'SALES', 'FOOD_SAFETY', 'AI',
]

export type ComputeStrategy = 'REALTIME' | 'CACHED' | 'PRECOMPUTED'
export const COMPUTE_STRATEGY_LABELS: Record<ComputeStrategy, string> = {
  REALTIME: '实时计算',
  CACHED: '缓存',
  PRECOMPUTED: '预计算',
}

export type AlertLevel = 'GREEN' | 'YELLOW' | 'RED'
export const ALERT_LEVEL_LABELS: Record<AlertLevel, string> = {
  GREEN: '正常',
  YELLOW: '黄色预警',
  RED: '红色预警',
}

export type ThresholdOperator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'BETWEEN'
export const THRESHOLD_OPERATOR_LABELS: Record<ThresholdOperator, string> = {
  GT: '>',
  GTE: '>=',
  LT: '<',
  LTE: '<=',
  EQ: '=',
  BETWEEN: '区间',
}

export interface Indicator {
  id: string
  factoryId: string
  code: string
  name: string
  description?: string
  category: IndicatorCategory
  unit?: string
  isActive: boolean
  computeStrategy: ComputeStrategy
  cacheTtlSeconds?: number
  lastValue?: number | null
  lastComputedAt?: string | null
  displayOrder?: number
  config?: Record<string, unknown>
  createdAt?: string
  updatedAt?: string
}

export interface IndicatorThreshold {
  id: string
  indicatorId: string
  alertLevel: AlertLevel
  operator: ThresholdOperator
  thresholdValue: number
  thresholdValueUpper?: number | null
  isActive: boolean
}

export interface IndicatorComputation {
  id: string
  indicatorId: string
  computeType: 'FORMULA' | 'PYTHON_ENDPOINT' | 'JPA_QUERY'
  computeSource: string
  params?: Record<string, unknown>
  priority: number
  isActive: boolean
}

export interface IndicatorVersion {
  id: string
  indicatorId: string
  periodStart: string
  periodEnd: string
  value: number
  computedAt: string
  alertLevel?: AlertLevel | null
  computeSource?: string
}

export interface IndicatorDetail extends Indicator {
  thresholds: IndicatorThreshold[]
  computations: IndicatorComputation[]
  latestVersion?: IndicatorVersion | null
}

export interface IndicatorTreeFlow {
  nodes: Array<{
    id: string
    label: string
    code: string
    category: IndicatorCategory
    unit?: string
    lastValue?: number | null
    x: number
    y: number
  }>
  edges: Array<{
    id: string
    source: string
    target: string
    weight?: number | null
  }>
}

export interface IndicatorLineage {
  indicatorId: string
  ancestors: Array<{ id: string; code: string; name: string; category: string; weight?: number; depth?: number }>
  descendants: Array<{ id: string; code: string; name: string; category: string; weight?: number; depth?: number }>
}

export interface BatchLineageNode {
  ancestorId: string
  ancestorType: string
  descendantId: string
  descendantType: string
  depth: number
}

export interface BatchLineageEdge {
  id: string
  edgeType: string
  sourceType: string
  sourceId: string
  targetType: string
  targetId: string
  quantityUsed?: number
  unit?: string
  eventTime?: string
  meta?: Record<string, unknown>
}

export interface BatchLineageGraph {
  batchId: string
  batchType: string
  ancestors: BatchLineageNode[]
  descendants: BatchLineageNode[]
  edges: BatchLineageEdge[]
  maxDepth: number
}

export interface VersionsPage {
  content: IndicatorVersion[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface TestEvaluateResult {
  formula: string
  variables: Record<string, unknown>
  status: 'OK' | 'FAILED'
  result?: unknown
  resultType?: string
  error?: string
}

// axios baseURL = '/api/mobile' — caller-side URLs start at /{factoryId}/...
const base = (factoryId: string) => `/${factoryId}/canvas-indicators`

export const indicatorsApi = {
  list(factoryId: string, category?: IndicatorCategory) {
    return request.get<Indicator[]>(base(factoryId), {
      params: category ? { category } : undefined,
    })
  },

  listCategories(factoryId: string) {
    return request.get<string[]>(`${base(factoryId)}/categories`)
  },

  detail(factoryId: string, id: string) {
    return request.get<IndicatorDetail>(`${base(factoryId)}/${id}`)
  },

  create(factoryId: string, payload: Partial<Indicator>) {
    return request.post<Indicator>(base(factoryId), payload)
  },

  update(factoryId: string, id: string, payload: Partial<Indicator>) {
    return request.put<Indicator>(`${base(factoryId)}/${id}`, payload)
  },

  deactivate(factoryId: string, id: string) {
    return request.delete<{ id: string; isActive: boolean }>(`${base(factoryId)}/${id}`)
  },

  listVersions(factoryId: string, id: string, page = 0, size = 20) {
    return request.get<VersionsPage>(`${base(factoryId)}/${id}/versions`, {
      params: { page, size },
    })
  },

  listMeasurements(factoryId: string, id: string, from?: string, to?: string) {
    return request.get<IndicatorVersion[]>(`${base(factoryId)}/${id}/measurements`, {
      params: { from, to },
    })
  },

  testEvaluate(factoryId: string, id: string, formula: string, variables: Record<string, unknown>) {
    return request.post<TestEvaluateResult>(`${base(factoryId)}/${id}/test-evaluate`, {
      formula,
      variables,
    })
  },

  recompute(factoryId: string, id: string) {
    return request.post<{ id: string; value: number; computedAt: string; note: string }>(
      `${base(factoryId)}/${id}/recompute`,
    )
  },

  lineage(factoryId: string, id: string) {
    return request.get<IndicatorLineage>(`${base(factoryId)}/${id}/lineage`)
  },

  tree(factoryId: string) {
    return request.get<IndicatorTreeFlow>(`${base(factoryId)}/tree`)
  },

  batchLineage(factoryId: string, batchType: string, batchId: string, maxDepth = 5) {
    return request.get<BatchLineageGraph>(
      `${base(factoryId)}/batch-lineage/${batchType}/${batchId}`,
      { params: { maxDepth } },
    )
  },
}

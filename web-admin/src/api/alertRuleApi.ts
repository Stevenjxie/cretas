/**
 * Canvas-Alerts Phase 2 API client.
 *
 * Backend: CanvasAlertController @ /api/mobile/{factoryId}/alerts
 *
 * Endpoints (per spec docs/superpowers/specs/2026-05-18-canvas-alerts-phase2-spec.md §6):
 *   GET    /rules                          列出告警规则
 *   POST   /rules                          创建规则
 *   PUT    /rules/{id}                     更新规则 (PATCH semantics)
 *   POST   /rules/{id}/toggle              启用/禁用切换
 *   DELETE /rules/{id}                     软删除
 *   GET    /events                         分页查询事件 (status filter)
 *   POST   /events/{eventId}/acknowledge   OPEN → ACKNOWLEDGED
 *   POST   /events/{eventId}/resolve       → RESOLVED
 *
 * RBAC: 仅 factory_super_admin / permission_admin 可访问 (backend class-level
 *       @RequireRole). 其他角色 403 → request.ts 全局 BLOCKING dialog 弹出.
 *
 * @since 2026-05-19 (Phase 2 UI)
 */
import request from './request'

// ==================== Enums (mirror backend) ====================

/**
 * 8 大业务预警类型 — 跟 backend AlertType.java 同步.
 */
export type AlertType =
  | 'INVENTORY_LOW'
  | 'INVENTORY_EXPIRING'
  | 'QUALITY_ANOMALY'
  | 'PO_AMOUNT_THRESHOLD'
  | 'SO_AMOUNT_THRESHOLD'
  | 'SALES_DECLINE'
  | 'CUSTOMER_PAYMENT_OVERDUE'
  | 'SUPPLIER_PAYABLE_DUE'
  | 'RESTAURANT_HEALTH_CHECK'

/**
 * UI 用 label map — 用户面显示中文.
 */
export const ALERT_TYPE_LABELS: Record<AlertType, string> = {
  INVENTORY_LOW: '低库存预警',
  INVENTORY_EXPIRING: '临期预警',
  QUALITY_ANOMALY: '质量异常',
  PO_AMOUNT_THRESHOLD: '采购金额超限',
  SO_AMOUNT_THRESHOLD: '销售金额异常',
  SALES_DECLINE: '销售下滑',
  CUSTOMER_PAYMENT_OVERDUE: '客户应收逾期',
  SUPPLIER_PAYABLE_DUE: '供应商应付到期',
  // 2026-07-11: 反回扣/经营异常预警 — 由 Python 餐饮经营体检报告驱动, 自动创建
  // + 定时扫描 (AlertRestaurantHealthCheckScheduler), 无 SpEL (built-in 逻辑).
  RESTAURANT_HEALTH_CHECK: '经营体检预警(反回扣/异常)',
}

/**
 * SpEL placeholder examples per alert type — 帮 UI 提示用户如何写 trigger 条件.
 */
export const ALERT_TYPE_SPEL_HINTS: Record<AlertType, string> = {
  INVENTORY_LOW: '#context.currentStock < #context.minStockLevel',
  INVENTORY_EXPIRING: '#context.daysToExpiry <= 30',
  QUALITY_ANOMALY: '#context.passRate < 95',
  PO_AMOUNT_THRESHOLD: '#context.amount >= 50000',
  SO_AMOUNT_THRESHOLD: '#context.amount >= 100000',
  SALES_DECLINE: '#context.declineRate >= 20',
  CUSTOMER_PAYMENT_OVERDUE: '#context.agingDays > 60',
  SUPPLIER_PAYABLE_DUE: '#context.daysToDue <= 7',
  // 内置逻辑 (RestaurantHealthAlertBridgeService 按 Python 诊断结果驱动), 无需 SpEL.
  RESTAURANT_HEALTH_CHECK: '',
}

export const ALERT_TYPES: AlertType[] = [
  'INVENTORY_LOW',
  'INVENTORY_EXPIRING',
  'QUALITY_ANOMALY',
  'PO_AMOUNT_THRESHOLD',
  'SO_AMOUNT_THRESHOLD',
  'SALES_DECLINE',
  'CUSTOMER_PAYMENT_OVERDUE',
  'SUPPLIER_PAYABLE_DUE',
  'RESTAURANT_HEALTH_CHECK',
]

/**
 * 告警严重度 — 跟 backend AlertSeverity.java 同步.
 *   LOW: 仅 IN_APP 显示, 不主动推送
 *   MID: IN_APP + 选定渠道
 *   HIGH: 立即推送全部启用渠道
 */
export type AlertSeverity = 'LOW' | 'MID' | 'HIGH'

export const ALERT_SEVERITY_LABELS: Record<AlertSeverity, string> = {
  LOW: '低',
  MID: '中',
  HIGH: '高',
}

export const ALERT_SEVERITY_TYPES: Record<AlertSeverity, 'info' | 'warning' | 'danger'> = {
  LOW: 'info',
  MID: 'warning',
  HIGH: 'danger',
}

/**
 * 告警事件状态机 — 跟 backend AlertEventStatus.java 同步.
 *   OPEN ──(用户 ack)──→ ACKNOWLEDGED ──(用户/系统 resolve)──→ RESOLVED
 *      └───────────────(直接 resolve, e.g. auto-clear)──────────────┘
 */
export type AlertEventStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'

export const ALERT_EVENT_STATUS_LABELS: Record<AlertEventStatus, string> = {
  OPEN: '待处理',
  ACKNOWLEDGED: '已确认',
  RESOLVED: '已解决',
}

export const ALERT_EVENT_STATUS_TYPES: Record<AlertEventStatus, 'danger' | 'warning' | 'success'> = {
  OPEN: 'danger',
  ACKNOWLEDGED: 'warning',
  RESOLVED: 'success',
}

/**
 * 通知渠道 — Phase 2 占位, Phase 3 fan-out 实际接 SDK.
 */
export type NotifyChannel = 'WECHAT' | 'DINGTALK' | 'EMAIL' | 'IN_APP' | 'SMS'

export const NOTIFY_CHANNEL_LABELS: Record<NotifyChannel, string> = {
  WECHAT: '微信',
  DINGTALK: '钉钉',
  EMAIL: '邮件',
  IN_APP: '站内消息',
  SMS: '短信',
}

export const NOTIFY_CHANNELS: NotifyChannel[] = [
  'IN_APP',
  'WECHAT',
  'DINGTALK',
  'EMAIL',
  'SMS',
]

// ==================== DTO shapes (mirror backend serialize) ====================

/**
 * 告警规则 — backend AlertRule.java + CanvasAlertController.serializeRule().
 *
 * 字段命名按 .claude/rules/field-naming-convention.md:
 *   Java entity camelCase → JSON camelCase → TypeScript camelCase.
 */
export interface AlertRule {
  id: string
  factoryId: string
  alertType: AlertType
  ruleName: string
  /** SpEL 表达式. 空 = 永远触发 (业务事件命中即触发). 非空 = 业务事件 + SpEL eval=true 才触发. */
  triggerConditionSpel: string | null
  severity: AlertSeverity
  enabled: boolean
  /** Phase 2: 多选枚举. Phase 3 fan-out 接 NotificationService. */
  notifyChannels: NotifyChannel[]
  /** 接收方角色 code list, e.g. ['factory_super_admin', 'procurement_manager'] */
  notifyRoles: string[]
  createdAt: string | null
  updatedAt: string | null
}

/**
 * 告警事件 — backend AlertEvent.java + CanvasAlertController.serializeEvent().
 */
export interface AlertEvent {
  id: string
  ruleId: string
  factoryId: string
  /** 触发实体类型, e.g. MATERIAL_BATCH / PURCHASE_ORDER / SALES_ORDER. */
  businessEntityType: string | null
  businessEntityId: string | null
  severity: AlertSeverity
  /** "冻猪蹄库存 25kg 已低于阈值 30kg" 类用户可读消息. */
  message: string
  status: AlertEventStatus
  ackedByUserId: number | null
  ackedAt: string | null
  resolvedByUserId: number | null
  resolvedAt: string | null
  createdAt: string | null
}

export interface CreateAlertRuleRequest {
  alertType: AlertType
  ruleName: string
  triggerConditionSpel?: string | null
  severity?: AlertSeverity
  enabled?: boolean
  notifyChannels?: NotifyChannel[]
  notifyRoles?: string[]
}

/**
 * PATCH semantics — 仅 set body 中包含的字段, 没传的 leave as-is.
 */
export interface UpdateAlertRuleRequest {
  ruleName?: string
  triggerConditionSpel?: string | null
  severity?: AlertSeverity
  enabled?: boolean
  notifyChannels?: NotifyChannel[]
  notifyRoles?: string[]
}

/**
 * Backend pagination envelope. Spring Page → { content, totalElements, totalPages, number, size }.
 */
export interface AlertEventPage {
  content: AlertEvent[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/**
 * 跟 ApprovalWorkflow API 一致 — request.ts 把后端 `{success, data, message}` 直接返出.
 */
export interface ApiResponse<T> {
  success: boolean
  data?: T
  message?: string
  code?: string
}

// ==================== API methods ====================

// axios baseURL = '/api/mobile' so caller-side URLs start at /{factoryId}/...
const base = (factoryId: string) => `/${factoryId}/alerts`

// --- Rules ---

export const listAlertRules = (factoryId: string) =>
  request.get<ApiResponse<AlertRule[]>>(`${base(factoryId)}/rules`)

export const createAlertRule = (factoryId: string, payload: CreateAlertRuleRequest) =>
  request.post<ApiResponse<AlertRule>>(`${base(factoryId)}/rules`, payload)

export const updateAlertRule = (factoryId: string, id: string, payload: UpdateAlertRuleRequest) =>
  request.put<ApiResponse<AlertRule>>(`${base(factoryId)}/rules/${id}`, payload)

export const toggleAlertRule = (factoryId: string, id: string) =>
  request.post<ApiResponse<AlertRule>>(`${base(factoryId)}/rules/${id}/toggle`)

export const deleteAlertRule = (factoryId: string, id: string) =>
  request.delete<ApiResponse<{ ruleId: string; deletedAt: string | null }>>(
    `${base(factoryId)}/rules/${id}`,
  )

// --- Events ---

/**
 * 查询事件列表 (paginated).
 *
 * @param status 'OPEN' / 'ACKNOWLEDGED' / 'RESOLVED' / 'ALL' / undefined (= 全部)
 */
export const listAlertEvents = (
  factoryId: string,
  status?: AlertEventStatus | 'ALL',
  page = 0,
  size = 20,
) => {
  const params = new URLSearchParams()
  if (status) params.append('status', status)
  params.append('page', String(page))
  params.append('size', String(size))
  return request.get<ApiResponse<AlertEventPage>>(
    `${base(factoryId)}/events?${params.toString()}`,
  )
}

export const acknowledgeAlertEvent = (factoryId: string, eventId: string) =>
  request.post<ApiResponse<AlertEvent>>(
    `${base(factoryId)}/events/${eventId}/acknowledge`,
  )

export const resolveAlertEvent = (factoryId: string, eventId: string) =>
  request.post<ApiResponse<AlertEvent>>(
    `${base(factoryId)}/events/${eventId}/resolve`,
  )

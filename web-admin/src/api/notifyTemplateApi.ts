/**
 * Notify Template + Logs API client — Phase 3 Canvas-Notify Step T7 (web-admin).
 *
 * Backend:
 *   - NotifyTemplateController @ /api/mobile/{factoryId}/notify/templates
 *   - NotifyLogController @ /api/mobile/{factoryId}/notify/logs
 *
 * 5 渠道: WECHAT / DINGTALK / EMAIL / SMS / IN_APP
 *
 * @since 2026-05-19 (Phase 3 implementation)
 */
import request from './request'

// ==================== Enums ====================

export type NotifyChannel = 'WECHAT' | 'DINGTALK' | 'EMAIL' | 'SMS' | 'IN_APP'

export type NotifyStatus = 'SENT' | 'FAILED'

/** Display labels for channels — used by chips/checkbox. */
export const NotifyChannelLabels: Record<NotifyChannel, string> = {
  WECHAT: '企业微信',
  DINGTALK: '钉钉',
  EMAIL: '邮件',
  SMS: '短信',
  IN_APP: '站内信',
}

/** Display labels for status. */
export const NotifyStatusLabels: Record<NotifyStatus, string> = {
  SENT: '已发送',
  FAILED: '失败',
}

export const ALL_NOTIFY_CHANNELS: NotifyChannel[] =
  ['WECHAT', 'DINGTALK', 'EMAIL', 'SMS', 'IN_APP']

// ==================== Wire types ====================

export interface NotifyTemplate {
  id?: string
  factoryId?: string
  templateCode: string
  title?: string
  bodyTemplate?: string
  channels: NotifyChannel[]
  variablesSchemaJson?: Record<string, string>
  createdAt?: string
  updatedAt?: string
}

export interface NotifyLog {
  id: string
  factoryId: string
  templateCode?: string
  recipientUserId?: number
  channel: NotifyChannel
  status: NotifyStatus
  errorMsg?: string
  sentAt?: string
  createdAt?: string
}

export interface TestSendRequest {
  templateCode: string
  channels?: NotifyChannel[]
  recipientUserIds?: number[]
  params?: Record<string, unknown>
}

export interface TestSendChannelResult {
  channel: NotifyChannel
  status: NotifyStatus
  errorMsg?: string
}

export interface TestSendResponse {
  templateCode: string
  renderedTitle?: string
  renderedBody?: string
  channels: NotifyChannel[]
  recipientUserIds: number[]
  results: TestSendChannelResult[]
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

interface ApiResponse<T> {
  success: boolean
  data: T
  message?: string
  code?: string | number
}

// ==================== API methods ====================

const templatesBase = (factoryId: string) =>
  `/${factoryId}/notify/templates`

const logsBase = (factoryId: string) =>
  `/${factoryId}/notify/logs`

/** List all templates for a factory. */
export const listTemplates = (factoryId: string) =>
  request.get<ApiResponse<NotifyTemplate[]>>(templatesBase(factoryId))

/** Create a template — 409 on duplicate templateCode. */
export const createTemplate = (factoryId: string, payload: NotifyTemplate) =>
  request.post<ApiResponse<NotifyTemplate>>(templatesBase(factoryId), payload)

/** Update title / body / channels / variables_schema_json. (templateCode NOT updatable.) */
export const updateTemplate = (
  factoryId: string,
  id: string,
  payload: Partial<NotifyTemplate>,
) => request.put<ApiResponse<NotifyTemplate>>(`${templatesBase(factoryId)}/${id}`, payload)

/** Soft-delete a template. */
export const deleteTemplate = (factoryId: string, id: string) =>
  request.delete<ApiResponse<void>>(`${templatesBase(factoryId)}/${id}`)

/** Test-send — renders template + fan-out to all channels + persists NotifyLog rows. */
export const testSend = (factoryId: string, payload: TestSendRequest) =>
  request.post<ApiResponse<TestSendResponse>>(
    `${templatesBase(factoryId)}/test-send`,
    payload,
  )

/** Paginated, filterable log query (channel / status / recipientUserId). */
export const listLogs = (
  factoryId: string,
  params: {
    channel?: NotifyChannel | ''
    status?: NotifyStatus | ''
    recipientUserId?: number
    page?: number
    size?: number
  } = {},
) =>
  request.get<ApiResponse<PageResponse<NotifyLog>>>(logsBase(factoryId), { params })

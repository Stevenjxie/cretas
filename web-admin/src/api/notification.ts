/**
 * 站内通知 API — 2026-07-11 (餐饮经营体检预警推送: 站内通知 + 短信).
 *
 * 对应后端 NotificationController. Base: /api/mobile/{factoryId}/notifications.
 * (axios baseURL = '/api/mobile', 见 request.ts — 这里的 path 不再重复前缀,
 * 见 .claude/rules 里 "web-admin 双 /api/mobile 前缀" 事故教训.)
 */
import request from './request'
import type { ApiResponse } from '@/types/api'

export type NotificationType = 'INFO' | 'WARNING' | 'ALERT' | 'SUCCESS' | 'SYSTEM'

export interface AppNotification {
  id: number
  factoryId: string
  userId: number | null
  title: string
  content: string
  type: NotificationType
  isRead: boolean
  readAt: string | null
  targetRole: string | null
  source: string | null
  sourceId: string | null
  actionUrl: string | null
  createdAt: string | null
}

export interface NotificationPage {
  content: AppNotification[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

function base(factoryId: string): string {
  return `/${factoryId}/notifications`
}

/** Bell badge 未读数 (含该用户 + 广播/角色通知). */
export function getUnreadCount(factoryId: string, userId?: number) {
  const q = userId != null ? `?userId=${userId}` : ''
  return request.get<ApiResponse<{ count: number }>>(`${base(factoryId)}/unread-count${q}`)
}

/** 最近 10 条通知 (factory-wide, 不区分 read/unread) — bell 下拉预览用. */
export function getRecentNotifications(factoryId: string) {
  return request.get<ApiResponse<AppNotification[]>>(`${base(factoryId)}/recent`)
}

/** 分页通知列表 — 提供 userId 时含该用户 + 广播通知, 未读优先排序. */
export function listNotifications(params: {
  factoryId: string
  userId?: number
  page?: number
  size?: number
  isRead?: boolean
}) {
  const q = new URLSearchParams()
  if (params.userId != null) q.set('userId', String(params.userId))
  q.set('page', String(params.page ?? 1))
  q.set('size', String(params.size ?? 20))
  if (params.isRead != null) q.set('isRead', String(params.isRead))
  return request.get<ApiResponse<NotificationPage>>(`${base(params.factoryId)}?${q.toString()}`)
}

/** 标记单条已读. */
export function markNotificationRead(factoryId: string, id: number) {
  return request.put<ApiResponse<AppNotification>>(`${base(factoryId)}/${id}/read`)
}

/** 标记指定用户全部已读 (含广播). */
export function markAllNotificationsReadForUser(factoryId: string, userId: number) {
  return request.put<ApiResponse<{ updatedCount: number }>>(
    `${base(factoryId)}/read-all?userId=${userId}`,
  )
}

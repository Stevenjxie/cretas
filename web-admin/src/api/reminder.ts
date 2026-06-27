/**
 * 提醒 API — Sprint 4 W2 S-REMIND-1.
 *
 * 对应后端 ReminderController. Base: /api/mobile/{factoryId}/reminders.
 */
import request from './request';
import type { ApiResponse } from '@/types/api';

export type ReminderType = 'PAYMENT_DUE';

export type ReminderStatus = 'PENDING' | 'SNOOZED' | 'DISMISSED';

export interface Reminder {
  id: string;
  factoryId: string;
  type: ReminderType;
  sourceType: string;
  sourceId: string;
  dueDate: string;
  assigneeId: number;
  status: ReminderStatus;
  snoozedUntil?: string | null;
  message?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReminderPage {
  content: Reminder[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

function getFactoryId(factoryId?: string): string {
  if (factoryId) return factoryId;
  const raw = localStorage.getItem('user');
  if (!raw) throw new Error('未登录');
  const parsed = JSON.parse(raw) as { factoryId?: string; factoryUser?: { factoryId?: string } };
  const fid = parsed?.factoryUser?.factoryId ?? parsed?.factoryId;
  if (!fid) throw new Error('当前账号未绑定工厂');
  return fid;
}

function basePath(factoryId?: string): string {
  return `/${getFactoryId(factoryId)}/reminders`;
}

/** 我的提醒列表 — status 默认 PENDING,SNOOZED. */
export function listReminders(params: {
  status?: string;
  page?: number;
  size?: number;
  factoryId?: string;
}): Promise<ApiResponse<ReminderPage>> {
  const q = new URLSearchParams();
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page ?? 0));
  q.set('size', String(params.size ?? 20));
  return request.get(`${basePath(params.factoryId)}?${q.toString()}`);
}

/** Bell badge 计数. */
export function getReminderBadge(
  factoryId?: string,
): Promise<{ success: boolean; data: { pending: number } }> {
  return request.get(`${basePath(factoryId)}/badge`);
}

/** Snooze. */
export function snoozeReminder(
  id: string,
  snoozedUntil: string,
  factoryId?: string,
): Promise<{ success: boolean; data: Reminder }> {
  return request.post(`${basePath(factoryId)}/${id}/snooze`, { snoozedUntil });
}

/** Dismiss. */
export function dismissReminder(
  id: string,
  factoryId?: string,
): Promise<{ success: boolean; data: Reminder }> {
  return request.post(`${basePath(factoryId)}/${id}/dismiss`, {});
}

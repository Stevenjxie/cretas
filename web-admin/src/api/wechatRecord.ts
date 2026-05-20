// Sprint 6 W2-C-1 — 微信记录 API client.
//
// Backend: WechatRecordController @RequestMapping
//   "/api/mobile/{factoryId}/customer/{customerId}/wechat-record"
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/..."
// (matches reminder.ts / customerTracking.ts pattern).
//
// 防呆 R3 dropdown: direction 必须是 INBOUND / OUTBOUND / INTERNAL.
// 防呆 R4 dedup: 5min 内同 (factoryId, customerId, messageContent) → 409 with actionHint.
import { get, post, put, del } from './request';

/** 微信记录方向 enum (matches backend WechatDirection). */
export type WechatDirection = 'INBOUND' | 'OUTBOUND' | 'INTERNAL';

export const WECHAT_DIRECTIONS: Array<{ value: WechatDirection; label: string; tagType: 'success' | 'primary' | 'info' }> = [
  { value: 'INBOUND', label: '客户来信', tagType: 'success' },
  { value: 'OUTBOUND', label: '我方去信', tagType: 'primary' },
  { value: 'INTERNAL', label: '内部备注', tagType: 'info' },
];

export function wechatDirectionLabel(d?: string): string {
  if (!d) return '—';
  return WECHAT_DIRECTIONS.find((x) => x.value === d)?.label || d;
}

export function wechatDirectionTagType(d?: string): 'success' | 'primary' | 'info' {
  return WECHAT_DIRECTIONS.find((x) => x.value === d)?.tagType || 'info';
}

export interface WechatRecord {
  id: number;
  factoryId: string;
  customerId: string;
  recordTime: string; // ISO datetime
  direction: WechatDirection;
  messageContent: string;
  createdBy: number;
  createdByName?: string;
  contactPerson?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateWechatRecordRequest {
  direction: WechatDirection;
  messageContent: string;
  createdBy: number;
  createdByName?: string;
  recordTime?: string;
  contactPerson?: string;
  remark?: string;
}

/** PUT body — only listed fields are mutable (factoryId / customerId / createdBy / recordTime 不可改). */
export interface UpdateWechatRecordRequest {
  direction?: WechatDirection;
  messageContent?: string;
  contactPerson?: string;
  remark?: string;
}

export interface WechatRecordPage {
  content: WechatRecord[];
  totalElements: number;
  totalPages: number;
  /** 防呆 R2: dialog header 显 "已有 N 条". */
  count: number;
}

function basePath(factoryId: string, customerId: string): string {
  return `/${factoryId}/customer/${customerId}/wechat-record`;
}

export async function listWechatRecords(
  factoryId: string,
  customerId: string,
  params: { page?: number; size?: number } = {},
): Promise<WechatRecordPage> {
  const res = await get<WechatRecordPage>(basePath(factoryId, customerId), { params });
  if (!res.success || !res.data) {
    return { content: [], totalElements: 0, totalPages: 0, count: 0 };
  }
  return res.data;
}

export async function getWechatRecord(
  factoryId: string,
  customerId: string,
  id: number,
): Promise<WechatRecord> {
  const res = await get<WechatRecord>(`${basePath(factoryId, customerId)}/${id}`);
  if (!res.success || !res.data) {
    throw new Error(res.message || '加载微信记录失败');
  }
  return res.data;
}

export async function createWechatRecord(
  factoryId: string,
  customerId: string,
  body: CreateWechatRecordRequest,
): Promise<WechatRecord> {
  const res = await post<WechatRecord>(basePath(factoryId, customerId), body);
  if (!res.success || !res.data) {
    throw new Error(res.message || '创建微信记录失败');
  }
  return res.data;
}

export async function updateWechatRecord(
  factoryId: string,
  customerId: string,
  id: number,
  body: UpdateWechatRecordRequest,
): Promise<WechatRecord> {
  const res = await put<WechatRecord>(`${basePath(factoryId, customerId)}/${id}`, body);
  if (!res.success || !res.data) {
    throw new Error(res.message || '更新微信记录失败');
  }
  return res.data;
}

export async function deleteWechatRecord(
  factoryId: string,
  customerId: string,
  id: number,
): Promise<void> {
  const res = await del(`${basePath(factoryId, customerId)}/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除微信记录失败');
  }
}

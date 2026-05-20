// Sprint 6 W2-C-2 — 通话记录 API client.
//
// Backend: CallRecordController @RequestMapping
//   "/api/mobile/{factoryId}/customer/{customerId}/call-record"
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
//
// 防呆 R1 (file size): max 50MB enforced server + client
// 防呆 R3 (callType): INCOMING / OUTGOING / MISSED enum dropdown
// 防呆 R4 (idempotent): 5min dedup (callTime ±5min) → 409 with actionHint

import { get, post, put, del } from './request';

/** 通话类型 enum (mirrors backend CallType). */
export type CallType = 'INCOMING' | 'OUTGOING' | 'MISSED';

export const CALL_TYPES: Array<{ value: CallType; label: string; tagType: 'success' | 'primary' | 'warning'; icon?: string }> = [
  { value: 'INCOMING', label: '客户呼入', tagType: 'success' },
  { value: 'OUTGOING', label: '业务员呼出', tagType: 'primary' },
  { value: 'MISSED', label: '漏接', tagType: 'warning' },
];

export function callTypeLabel(t?: string): string {
  if (!t) return '—';
  return CALL_TYPES.find((x) => x.value === t)?.label || t;
}

export function callTypeTagType(t?: string): 'success' | 'primary' | 'warning' | 'info' {
  return CALL_TYPES.find((x) => x.value === t)?.tagType || 'info';
}

/** Transcript status values written by backend. */
export type TranscriptStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | null;

export interface CallRecord {
  id: number;
  factoryId: string;
  customerId: string;
  callTime: string; // ISO datetime
  durationSec?: number | null;
  callType: CallType;
  contactName?: string;
  contactPhone?: string;
  audioOssUrl?: string;
  audioFilename?: string;
  audioSizeBytes?: number;
  audioMimeType?: string;
  transcriptText?: string;
  transcriptStatus?: TranscriptStatus;
  remark?: string;
  createdBy: number;
  createdByName?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CallRecordPage {
  content: CallRecord[];
  totalElements: number;
  totalPages: number;
  /** 防呆 R2: dialog header 显 "已有 N 条". */
  count: number;
}

export interface UpdateCallRecordRequest {
  contactName?: string;
  contactPhone?: string;
  remark?: string;
  durationSec?: number;
}

/** 防呆 R1: 50 MB max audio file size (client mirror of backend MAX_AUDIO_FILE_SIZE). */
export const MAX_AUDIO_FILE_SIZE_BYTES = 50 * 1024 * 1024;

function basePath(factoryId: string, customerId: string): string {
  return `/${factoryId}/customer/${customerId}/call-record`;
}

export async function listCallRecords(
  factoryId: string,
  customerId: string,
  params: { page?: number; size?: number } = {},
): Promise<CallRecordPage> {
  const res = await get<CallRecordPage>(basePath(factoryId, customerId), { params });
  if (!res.success || !res.data) {
    return { content: [], totalElements: 0, totalPages: 0, count: 0 };
  }
  return res.data;
}

export async function getCallRecord(
  factoryId: string,
  customerId: string,
  id: number,
): Promise<CallRecord> {
  const res = await get<CallRecord>(`${basePath(factoryId, customerId)}/${id}`);
  if (!res.success || !res.data) {
    throw new Error(res.message || '加载通话记录失败');
  }
  return res.data;
}

/**
 * Create call record. Uses multipart/form-data so optional audio file can be uploaded
 * in the same request. Server triggers async Whisper transcription.
 *
 * 防呆 R4: 5min dedup window 触发 409 with actionHint → caller catches and confirms.
 */
export async function createCallRecord(
  factoryId: string,
  customerId: string,
  fields: {
    callType: CallType;
    callTime?: string;        // ISO datetime, optional (server defaults to now)
    durationSec?: number;
    contactName?: string;
    contactPhone?: string;
    remark?: string;
    createdBy: number;
    createdByName?: string;
    audio?: File | null;       // optional audio File (mp3/wav/m4a/ogg/webm)
  },
): Promise<CallRecord> {
  const fd = new FormData();
  fd.append('callType', fields.callType);
  if (fields.callTime) fd.append('callTime', fields.callTime);
  if (fields.durationSec !== undefined && fields.durationSec !== null) {
    fd.append('durationSec', String(fields.durationSec));
  }
  if (fields.contactName) fd.append('contactName', fields.contactName);
  if (fields.contactPhone) fd.append('contactPhone', fields.contactPhone);
  if (fields.remark) fd.append('remark', fields.remark);
  fd.append('createdBy', String(fields.createdBy));
  if (fields.createdByName) fd.append('createdByName', fields.createdByName);
  if (fields.audio) {
    if (fields.audio.size > MAX_AUDIO_FILE_SIZE_BYTES) {
      throw new Error(
        `录音文件大小 ${(fields.audio.size / 1024 / 1024).toFixed(2)} MB 超过 50 MB 限制`,
      );
    }
    fd.append('audio', fields.audio, fields.audio.name);
  }

  const res = await post<CallRecord>(
    basePath(factoryId, customerId),
    fd,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '创建通话记录失败');
  }
  return res.data;
}

export async function updateCallRecord(
  factoryId: string,
  customerId: string,
  id: number,
  body: UpdateCallRecordRequest,
): Promise<CallRecord> {
  const res = await put<CallRecord>(`${basePath(factoryId, customerId)}/${id}`, body);
  if (!res.success || !res.data) {
    throw new Error(res.message || '更新通话记录失败');
  }
  return res.data;
}

export async function deleteCallRecord(
  factoryId: string,
  customerId: string,
  id: number,
): Promise<void> {
  const res = await del(`${basePath(factoryId, customerId)}/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除通话记录失败');
  }
}

/** Format byte size for display (防呆 R1 readable label). */
export function formatFileSize(bytes?: number | null): string {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/** Format duration seconds → mm:ss / hh:mm:ss. */
export function formatDuration(sec?: number | null): string {
  if (sec == null) return '—';
  const s = Math.floor(sec);
  const hh = Math.floor(s / 3600);
  const mm = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  if (hh > 0) {
    return `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
  }
  return `${mm}:${String(ss).padStart(2, '0')}`;
}

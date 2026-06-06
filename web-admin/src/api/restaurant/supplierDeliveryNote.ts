/**
 * 供应商送货单 OCR / 人工录入 API (G7 取数自动化 Tier A)。
 *
 * 后端: Java /api/mobile/{factoryId}/restaurant/supplier-delivery-notes
 * 统一响应 {success, data, message}。FormData 上传由 request 自动处理 multipart。
 */
import { get, post, put, del } from '../request';
import type { ApiResponse } from '@/types/api';

export interface SupplierDeliveryNoteLineDto {
  id?: number;
  ingredientName: string;
  rawMaterialTypeId?: string | null;
  quantity?: number | null;
  unit?: string | null;
  unitPrice?: number | null;
  baselineUnitPrice?: number | null;
  priceVarianceRate?: number | null;
  priceAnomalyFlag?: boolean | null;
  priceAnomalyReasonCode?: string | null;
  priceAnomalyExplanation?: string | null;
  lineAmount?: number | null;
  ocrConfidence?: number | null;
  qcResult?: string | null;
  materialBatchId?: string | null;
  remark?: string | null;
}

export type DeliveryPostingStatus = 'UNPOSTED' | 'POSTING' | 'PENDING' | 'POSTED' | 'FAILED';
export type PriceAnomalyApprovalStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED';

export interface SupplierDeliveryNoteDto {
  id: string;
  factoryId: string;
  sourceType: 'OCR' | 'MANUAL';
  photoOssUrl?: string | null;
  supplierId?: string | null;
  supplierName?: string | null;
  deliveryDate: string;
  noteNumber?: string | null;
  totalAmount?: number | null;
  ocrConfidence?: number | null;
  ocrErrorMessage?: string | null;
  status: 'DRAFT' | 'CONFIRMED' | 'REJECTED';
  postingStatus?: DeliveryPostingStatus | null;
  receiveRecordId?: string | null;
  postedAt?: string | null;
  postedBy?: string | null;
  postingError?: string | null;
  rejectReasonCode?: string | null;
  rejectReasonNote?: string | null;
  priceAnomalyApprovalStatus?: PriceAnomalyApprovalStatus | null;
  priceAnomalySubmittedBy?: number | null;
  priceAnomalySubmittedAt?: string | null;
  priceAnomalyApprovedBy?: number | null;
  priceAnomalyApprovedAt?: string | null;
  priceAnomalyRejectedBy?: number | null;
  priceAnomalyRejectedAt?: string | null;
  priceAnomalyApprovalComment?: string | null;
  sourceRequisitionId?: string | null;
  procurementConfirmedBy?: number | null;
  procurementConfirmedAt?: string | null;
  supplierContactNote?: string | null;
  voiceAudioUrl?: string | null;
  voiceTranscriptText?: string | null;
  supplierQuotePhotoUrls?: string[] | null;
  expectedDeliveryDate?: string | null;
  lowConfidenceWarning?: boolean;
  lines?: SupplierDeliveryNoteLineDto[];
}

export interface PriceAnomalyApprovalBody {
  comment?: string;
}

export interface NoteLimits {
  month: string;
  confirmedCount: number;
  draftCount: number;
  totalCount: number;
}

export interface RejectBody {
  rejectReasonCode: string;
  rejectReasonNote?: string;
}

export interface ManualCreateBody {
  supplierId?: string;
  supplierName?: string;
  deliveryDate?: string;
  noteNumber?: string;
  lines: SupplierDeliveryNoteLineDto[];
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

const base = (factoryId: string) => `/${factoryId}/restaurant/supplier-delivery-notes`;

/** OCR 解析上传照片 → 返回草稿 note + 行项。 */
export function ocrParseNote(
  factoryId: string,
  formData: FormData,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  // 上传 + OCR 可能较慢, 单独放宽超时。
  return post(`${base(factoryId)}/ocr-parse`, formData, { timeout: 600000 });
}

/** 手动录入 (OCR 失败/低置信兜底, D2)。 */
export function createManualNote(
  factoryId: string,
  body: ManualCreateBody,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return post(`${base(factoryId)}/manual`, body);
}

/** Rule 1: 本月录入限制信息。 */
export function getNoteLimits(
  factoryId: string,
  month: string,
): Promise<ApiResponse<NoteLimits>> {
  return get(`${base(factoryId)}/limits`, { params: { month } });
}

/** 分页列表。 */
export function getNoteList(
  factoryId: string,
  params: { status?: string; page?: number; size?: number },
): Promise<ApiResponse<PageResult<SupplierDeliveryNoteDto>>> {
  return get(`${base(factoryId)}`, { params });
}

/** 详情 (含行项)。 */
export function getNoteDetail(
  factoryId: string,
  noteId: string,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return get(`${base(factoryId)}/${noteId}`);
}

/** 确认验收入库 → 生成库存批次。 */
export function confirmNote(
  factoryId: string,
  noteId: string,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return put(`${base(factoryId)}/${noteId}/confirm`, {}, { timeout: 600000 });
}

/** 拒绝 (Rule 3 标准原因码)。 */
export function rejectNote(
  factoryId: string,
  noteId: string,
  body: RejectBody,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return put(`${base(factoryId)}/${noteId}/reject`, body);
}

/** 编辑行项。 */
export function updateNoteLines(
  factoryId: string,
  noteId: string,
  lines: SupplierDeliveryNoteLineDto[],
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return put(`${base(factoryId)}/${noteId}/lines`, lines);
}

/** 删除草稿 (软删除)。 */
export function deleteNote(
  factoryId: string,
  noteId: string,
): Promise<ApiResponse<void>> {
  return del(`${base(factoryId)}/${noteId}`);
}

/** 提交价格异常老板审批。 */
export function submitPriceAnomalyApproval(
  factoryId: string,
  noteId: string,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return post(`${base(factoryId)}/${noteId}/price-anomaly/submit`);
}

/** 老板批准价格异常。 */
export function approvePriceAnomaly(
  factoryId: string,
  noteId: string,
  body?: PriceAnomalyApprovalBody,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return post(`${base(factoryId)}/${noteId}/price-anomaly/approve`, body || {});
}

/** 老板驳回价格异常。 */
export function rejectPriceAnomaly(
  factoryId: string,
  noteId: string,
  body?: PriceAnomalyApprovalBody,
): Promise<ApiResponse<SupplierDeliveryNoteDto>> {
  return post(`${base(factoryId)}/${noteId}/price-anomaly/reject`, body || {});
}

/** 待审批价格异常列表。 */
export function getPendingPriceAnomalyApprovals(
  factoryId: string,
  params?: { page?: number; size?: number },
): Promise<ApiResponse<PageResult<SupplierDeliveryNoteDto>>> {
  return get(`${base(factoryId)}/price-anomaly/pending`, { params });
}

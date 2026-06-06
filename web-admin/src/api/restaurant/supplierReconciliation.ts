import { get, post } from '../request';
import type { ApiResponse, PageResponse } from '@/types/api';

export type SupplierReconciliationStatus = 'DRAFT' | 'CONFIRMED';
export type SupplierReconciliationLineStatus =
  | 'MATCHED'
  | 'MISSING_AP'
  | 'AMOUNT_MISMATCH'
  | 'UNMATCHED_AP'
  | 'INFO';

export interface SupplierMonthlyReconciliationLineDto {
  id: number;
  lineType: 'DELIVERY_NOTE' | 'AP_TRANSACTION';
  deliveryNoteId?: string | null;
  deliveryNoteNumber?: string | null;
  deliveryDate?: string | null;
  deliveryAmount?: number | null;
  apTransactionId?: string | null;
  apTransactionNumber?: string | null;
  transactionDate?: string | null;
  transactionType?: string | null;
  apAmount?: number | null;
  differenceAmount?: number | null;
  lineStatus: SupplierReconciliationLineStatus;
  remark?: string | null;
  photoOssUrl?: string | null;
  voiceAudioUrl?: string | null;
  voiceTranscriptText?: string | null;
  supplierContactNote?: string | null;
  priceAnomalyApprovalStatus?: string | null;
  priceAnomalyApprovedBy?: number | null;
  priceAnomalyApprovedAt?: string | null;
  priceAnomalyApprovalComment?: string | null;
  payableTransactionId?: string | null;
  payablePostedAt?: string | null;
}

export interface SupplierMonthlyReconciliationDto {
  id: string;
  factoryId: string;
  supplierId: string;
  supplierName?: string | null;
  month: string;
  periodStart: string;
  periodEnd: string;
  status: SupplierReconciliationStatus;
  deliveryNoteCount: number;
  apTransactionCount: number;
  deliveryTotal?: number | null;
  apInvoiceTotal?: number | null;
  apPaymentTotal?: number | null;
  apAdjustmentTotal?: number | null;
  differenceAmount?: number | null;
  netPayableAmount?: number | null;
  confirmedBy?: number | null;
  confirmedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  lines?: SupplierMonthlyReconciliationLineDto[];
}

export interface DraftReconciliationBody {
  supplierId: string;
  month: string;
}

const base = (factoryId: string) => `/${factoryId}/restaurant/supplier-reconciliations`;

export function listSupplierReconciliations(
  factoryId: string,
  params: { supplierId?: string; page?: number; size?: number },
): Promise<ApiResponse<PageResponse<SupplierMonthlyReconciliationDto>>> {
  return get(`${base(factoryId)}`, { params });
}

export function getSupplierReconciliation(
  factoryId: string,
  id: string,
): Promise<ApiResponse<SupplierMonthlyReconciliationDto>> {
  return get(`${base(factoryId)}/${id}`);
}

export function createSupplierReconciliationDraft(
  factoryId: string,
  body: DraftReconciliationBody,
): Promise<ApiResponse<SupplierMonthlyReconciliationDto>> {
  return post(`${base(factoryId)}/draft`, body);
}

export function confirmSupplierReconciliation(
  factoryId: string,
  id: string,
): Promise<ApiResponse<SupplierMonthlyReconciliationDto>> {
  return post(`${base(factoryId)}/${id}/confirm`);
}

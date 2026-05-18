// Sprint 4 W1 S-CUSTOMER-TAB-1: tab 20 业务员变更 history list API.
//
// Backend: GET /api/mobile/{factoryId}/customer-sales-user-history?customerId=&page=&size=
//          (Phase A A5)
import { get } from './request';
import { ApiError } from '@/types/api';

export interface CustomerSalesUserHistory {
  id: string;
  factoryId: string;
  customerId: string;
  previousSalesUserId: number | null;
  newSalesUserId: number | null;
  changedBy: number | null;
  changedAt: string; // ISO datetime
  reason?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface HistoryPage {
  content: CustomerSalesUserHistory[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export async function listHistory(
  factoryId: string,
  customerId: string,
  page = 1,
  size = 20,
): Promise<HistoryPage> {
  const res = await get<HistoryPage>(`/${factoryId}/customer-sales-user-history`, {
    params: { customerId, page, size },
  });
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '业务员变更记录加载失败', res.code);
  }
  return res.data;
}

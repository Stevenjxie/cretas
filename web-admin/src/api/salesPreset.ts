/**
 * Sprint 4 W2 S-REPORTS-PRESETS — Vue client to Python /api/smartbi/{factoryId}/sales-preset/*.
 *
 * Direct Python fetch (consistent w/ revenue-report / restaurant-analytics pattern).
 * No Java pass-through.
 */
import { pythonFetch } from '@/api/smartbi/common';
import { getFactoryId } from '@/api/smartbi/common';

const base = () => `/api/smartbi/${getFactoryId()}/sales-preset`;

export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

export interface TaxBreakdown {
  revenue: number;
  taxableAmount: number;
  taxAmount: number;
  totalAmountWithTax: number;
}

export interface DailyReport extends TaxBreakdown {
  date: string;
  orderCount: number;
  paid: number;
  unpaid: number;
}

export interface MonthlyReport extends TaxBreakdown {
  yearMonth: string;
  orderCount: number;
  paid: number;
  unpaid: number;
  daily: Array<{ date: string; orderCount: number } & TaxBreakdown>;
}

export interface YearlyReport extends TaxBreakdown {
  year: number;
  orderCount: number;
  paid: number;
  monthly: Array<{ month: number; orderCount: number } & TaxBreakdown>;
}

export interface CustomerRankRow extends TaxBreakdown {
  rank: number;
  customerId: string;
  customerName: string;
  orderCount: number;
  paid: number;
}

export interface ProductRankRow extends TaxBreakdown {
  rank: number;
  productTypeId: string;
  productName: string | null;
  totalQty: number;
  unit: string | null;
  orderCount: number;
}

export function fetchDailyReport(date?: string): Promise<ApiResponse<DailyReport>> {
  const q = date ? `?date=${encodeURIComponent(date)}` : '';
  return pythonFetch(`${base()}/daily-report${q}`);
}

export function fetchMonthlyReport(yearMonth?: string): Promise<ApiResponse<MonthlyReport>> {
  const q = yearMonth ? `?yearMonth=${encodeURIComponent(yearMonth)}` : '';
  return pythonFetch(`${base()}/monthly-report${q}`);
}

export function fetchYearlyReport(year?: number): Promise<ApiResponse<YearlyReport>> {
  const q = year ? `?year=${year}` : '';
  return pythonFetch(`${base()}/yearly-report${q}`);
}

export function fetchCustomerRank(
  startDate?: string, endDate?: string, limit = 20,
): Promise<ApiResponse<{ startDate: string; endDate: string; rank: CustomerRankRow[] }>> {
  const q = new URLSearchParams();
  if (startDate) q.set('startDate', startDate);
  if (endDate) q.set('endDate', endDate);
  q.set('limit', String(limit));
  return pythonFetch(`${base()}/customer-rank?${q.toString()}`);
}

export function fetchProductRank(
  startDate?: string, endDate?: string, limit = 20,
): Promise<ApiResponse<{ startDate: string; endDate: string; rank: ProductRankRow[] }>> {
  const q = new URLSearchParams();
  if (startDate) q.set('startDate', startDate);
  if (endDate) q.set('endDate', endDate);
  q.set('limit', String(limit));
  return pythonFetch(`${base()}/product-rank?${q.toString()}`);
}

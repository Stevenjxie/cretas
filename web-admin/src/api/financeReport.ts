/**
 * Sprint 7 T3 — 财务三表 API client.
 *
 * <p>路径前缀 /api/mobile/{factoryId}/finance/report.
 */

import { get } from '@/api/request';
import axios from 'axios';

export interface LineItem {
  accountCode: string;
  accountName: string;
  amount: number;
}

export interface BalanceSheetDTO {
  factoryId: string;
  year: number;
  month: number;
  /** 期间结账状态 — OPEN/PENDING_CLOSE/CLOSED */
  periodStatus: 'OPEN' | 'PENDING_CLOSE' | 'CLOSED';
  assets: LineItem[];
  liabilities: LineItem[];
  equity: LineItem[];
  totalAssets: number;
  totalLiabilities: number;
  totalEquity: number;
  totalLiabilitiesAndEquity: number;
  /** true 当 |totalAssets - totalLiabilitiesAndEquity| <= 0.01. */
  balanceCheck: boolean;
  generatedAt: string;
}

export interface IncomeStatementDTO {
  factoryId: string;
  startYear: number;
  startMonth: number;
  endYear: number;
  endMonth: number;
  revenues: LineItem[];
  costs: LineItem[];
  expenses: LineItem[];
  totalRevenue: number;
  totalCost: number;
  grossProfit: number;
  totalExpense: number;
  operatingProfit: number;
  incomeTax: number;
  netProfit: number;
  generatedAt: string;
}

export interface CashFlowActivity {
  counterpartCode: string;
  counterpartName: string;
  inflow: number;
  outflow: number;
  netAmount: number;
}

export interface CashFlowDTO {
  factoryId: string;
  startYear: number;
  startMonth: number;
  endYear: number;
  endMonth: number;
  operatingActivities: CashFlowActivity[];
  investingActivities: CashFlowActivity[];
  financingActivities: CashFlowActivity[];
  operatingNetCashFlow: number;
  investingNetCashFlow: number;
  financingNetCashFlow: number;
  netIncreaseInCash: number;
  beginningCash: number;
  endingCash: number;
  generatedAt: string;
}

/**
 * 资产负债表 — 截至 year/month 月底.
 */
export function getBalanceSheet(factoryId: string, year: number, month: number) {
  return get<BalanceSheetDTO>(
    `/${factoryId}/finance/report/balance-sheet`,
    { params: { year, month } }
  );
}

/**
 * 利润表 — 期间累计.
 */
export function getIncomeStatement(
  factoryId: string,
  startYear: number,
  startMonth: number,
  endYear: number,
  endMonth: number,
) {
  return get<IncomeStatementDTO>(
    `/${factoryId}/finance/report/income-statement`,
    { params: { startYear, startMonth, endYear, endMonth } }
  );
}

/**
 * 现金流量表 — 期间累计, 直接法.
 */
export function getCashFlow(
  factoryId: string,
  startYear: number,
  startMonth: number,
  endYear: number,
  endMonth: number,
) {
  return get<CashFlowDTO>(
    `/${factoryId}/finance/report/cash-flow`,
    { params: { startYear, startMonth, endYear, endMonth } }
  );
}

/**
 * 导出资产负债表 xlsx.
 * 使用 blob 下载, 调用方负责触发浏览器保存.
 */
export async function exportBalanceSheet(
  factoryId: string,
  year: number,
  month: number,
): Promise<void> {
  const response = await axios.get(
    `/api/mobile/${factoryId}/finance/report/balance-sheet/export`,
    { params: { year, month }, responseType: 'blob' },
  );
  const blob = new Blob([response.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const cd = response.headers['content-disposition'] ?? '';
  const match = cd.match(/filename\*=UTF-8''(.+)/i);
  a.download = match ? decodeURIComponent(match[1]) : `资产负债表_${year}${String(month).padStart(2, '0')}.xlsx`;
  a.href = url;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * 导出现金流量表 xlsx.
 */
export async function exportCashFlow(
  factoryId: string,
  startYear: number,
  startMonth: number,
  endYear: number,
  endMonth: number,
): Promise<void> {
  const response = await axios.get(
    `/api/mobile/${factoryId}/finance/report/cash-flow/export`,
    { params: { startYear, startMonth, endYear, endMonth }, responseType: 'blob' },
  );
  const blob = new Blob([response.data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const cd = response.headers['content-disposition'] ?? '';
  const match = cd.match(/filename\*=UTF-8''(.+)/i);
  a.download = match
    ? decodeURIComponent(match[1])
    : `现金流量表_${startYear}${String(startMonth).padStart(2, '0')}_${endYear}${String(endMonth).padStart(2, '0')}.xlsx`;
  a.href = url;
  a.click();
  URL.revokeObjectURL(url);
}

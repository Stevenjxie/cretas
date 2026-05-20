/**
 * Sprint 7 T3 — 财务三表 API client.
 *
 * <p>路径前缀 /api/mobile/{factoryId}/finance/report.
 */

import { get } from '@/api/request';

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
    `/api/mobile/${factoryId}/finance/report/balance-sheet`,
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
    `/api/mobile/${factoryId}/finance/report/income-statement`,
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
    `/api/mobile/${factoryId}/finance/report/cash-flow`,
    { params: { startYear, startMonth, endYear, endMonth } }
  );
}

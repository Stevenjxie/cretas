/**
 * Sprint 6 Track W4-B: WagePolicy + HourlyRateRule + WageCalculation API client.
 *
 * Backend: WagePolicyController at /api/mobile/{factoryId}/wage-policy
 *
 * @since 2026-05-19
 */
import { get, post, del } from './request';

export type WageMode = 'PIECE_RATE' | 'HOURLY' | 'MIXED';

export const WAGE_MODE_LABEL: Record<WageMode, string> = {
  PIECE_RATE: '按件 (PR #57 默认)',
  HOURLY: '按时 (工时 × 时薪)',
  MIXED: '混合 (按时 + 计件)',
};

export interface WagePolicy {
  id?: number;
  factoryId?: string;
  employeeId?: number | null;
  mode: WageMode;
  mixedFormulaHint?: string;
  isActive?: boolean;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface HourlyRateRule {
  id?: number;
  factoryId?: string;
  employeeId: number;
  baseHourlyRate: number;
  overtimeMultiplier?: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  isActive?: boolean;
  notes?: string;
}

export interface WageCalculation {
  id: number;
  factoryId: string;
  employeeId: number;
  employeeName?: string;
  periodMonth: string;
  mode: WageMode;
  hourlyAmount: number;
  overtimeAmount: number;
  pieceRateAmount: number;
  totalAmount: number;
  totalHours: number;
  overtimeHours: number;
  totalPieceCount: number;
  hourlyRuleId?: number | null;
  pieceRuleId?: number | null;
  notes?: string;
}

const base = (factoryId: string) => `/${factoryId}/wage-policy`;

// ==================== WagePolicy ====================

export function listPolicies(factoryId: string) {
  return get<WagePolicy[]>(`${base(factoryId)}/policies`);
}

export function resolveMode(factoryId: string, employeeId: number) {
  return get<{ factoryId: string; employeeId: number; resolvedMode: WageMode }>(
    `${base(factoryId)}/policies/resolve`,
    { params: { employeeId } },
  );
}

export function savePolicy(factoryId: string, policy: WagePolicy) {
  return post<WagePolicy>(`${base(factoryId)}/policies`, policy);
}

export function deletePolicy(factoryId: string, id: number) {
  return del<void>(`${base(factoryId)}/policies/${id}`);
}

// ==================== HourlyRateRule ====================

export function listHourlyRules(factoryId: string, employeeId?: number) {
  const params: Record<string, unknown> = {};
  if (employeeId != null) params.employeeId = employeeId;
  return get<HourlyRateRule[]>(`${base(factoryId)}/hourly-rules`, { params });
}

export function saveHourlyRule(factoryId: string, rule: HourlyRateRule) {
  return post<HourlyRateRule>(`${base(factoryId)}/hourly-rules`, rule);
}

export function deleteHourlyRule(factoryId: string, id: number) {
  return del<void>(`${base(factoryId)}/hourly-rules/${id}`);
}

export function findEffectiveHourlyRule(factoryId: string, employeeId: number, date: string) {
  return get<HourlyRateRule | null>(`${base(factoryId)}/hourly-rules/effective`, {
    params: { employeeId, date },
  });
}

// ==================== 月度计算 ====================

export function calculateMonthly(factoryId: string, employeeId: number, month: string) {
  return post<WageCalculation>(
    `${base(factoryId)}/calculate-monthly?employeeId=${employeeId}&month=${month}`,
    null,
  );
}

export function calculateMonthlyBatch(factoryId: string, month: string) {
  return post<{
    factoryId: string;
    month: string;
    workerCount: number;
    successCount: number;
    failedCount: number;
    failedReasons?: string[];
  }>(`${base(factoryId)}/calculate-monthly-batch?month=${month}`, null);
}

export function listCalculations(factoryId: string, month: string) {
  return get<WageCalculation[]>(`${base(factoryId)}/calculations`, { params: { month } });
}

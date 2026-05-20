/**
 * Sprint 7 wave 2 T5 — Sales target + commission Vue API client.
 *
 * Backend: /api/mobile/{factoryId}/sales-target/* + /commission/*
 * axios baseURL = '/api/mobile' so caller-side paths start with `/{factoryId}/...`
 */
import { get, post, put, del } from './request';
import { ApiError } from '@/types/api';
import { getFactoryId } from '@/api/smartbi/common';

// ==================== Types ====================

export type TargetPeriod = 'MONTH' | 'QUARTER' | 'YEAR';
export type CommissionStatus = 'PENDING' | 'PAID' | 'CANCELLED';

export interface SalesTarget {
  id: string;
  factoryId: string;
  ownerId: number;
  period: TargetPeriod;
  year: number;
  periodNum: number;
  targetAmount: number;
  createdBy: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface SalesTargetProgress {
  ownerId: number;
  period: TargetPeriod;
  periodDisplay: string;
  year: number;
  periodNum: number;
  target: number;
  actual: number;
  gap: number;
  completionRate: number;
  rank: number | null;
}

export interface LeaderboardEntry {
  rank: number;
  ownerId: number;
  actual: number;
  target: number;
  completionRate: number;
}

export interface Leaderboard {
  period: TargetPeriod;
  periodDisplay: string;
  year: number;
  periodNum: number;
  entries: LeaderboardEntry[];
  teamTarget: number;
  teamActual: number;
  teamCompletionRate: number;
}

export interface CommissionRule {
  id: string;
  factoryId: string;
  salesId: number | null;
  customerType: string | null;
  percentage: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  active: boolean;
  createdBy: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface Commission {
  id: string;
  factoryId: string;
  salesOpportunityId: string;
  ruleId: string;
  salesId: number;
  percentage: number;
  amount: number;
  status: CommissionStatus;
  paidAt: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CommissionListResponse {
  content: Commission[];
  totalElements: number;
  totalPages: number;
  count: number;
}

// ==================== Sales Target API ====================

export async function setTarget(body: {
  ownerId: number;
  period: TargetPeriod;
  year: number;
  periodNum: number;
  targetAmount: number;
  createdBy: number;
}): Promise<SalesTarget> {
  const fid = getFactoryId();
  const res = await post<SalesTarget>(`/${fid}/sales-target`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '保存目标失败', res.code);
  }
  return res.data;
}

export async function deleteTarget(id: string): Promise<void> {
  const fid = getFactoryId();
  const res = await del(`/${fid}/sales-target/${id}`);
  if (!res.success) {
    throw new ApiError(res.message || '删除失败', res.code);
  }
}

export async function getMyTargets(ownerId: number): Promise<SalesTarget[]> {
  const fid = getFactoryId();
  const res = await get<SalesTarget[]>(`/${fid}/sales-target/my`, {
    params: { ownerId },
  });
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '查询目标失败', res.code);
  }
  return res.data;
}

export async function getProgress(
  ownerId: number,
  period: TargetPeriod,
  year: number,
  periodNum: number,
): Promise<SalesTargetProgress> {
  const fid = getFactoryId();
  const res = await get<SalesTargetProgress>(`/${fid}/sales-target/progress`, {
    params: { ownerId, period, year, periodNum },
  });
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '查询进度失败', res.code);
  }
  return res.data;
}

export async function getLeaderboard(
  period: TargetPeriod,
  year: number,
  periodNum: number,
  limit = 10,
): Promise<Leaderboard> {
  const fid = getFactoryId();
  const res = await get<Leaderboard>(`/${fid}/sales-target/leaderboard`, {
    params: { period, year, periodNum, limit },
  });
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '查询排行榜失败', res.code);
  }
  return res.data;
}

// ==================== Commission API ====================

export async function listCommissions(params: {
  status?: CommissionStatus | '';
  salesId?: number;
  page?: number;
  size?: number;
}): Promise<CommissionListResponse> {
  const fid = getFactoryId();
  const query: Record<string, string | number> = {
    page: params.page ?? 0,
    size: params.size ?? 20,
  };
  if (params.status) query.status = params.status;
  if (params.salesId !== undefined) query.salesId = params.salesId;
  const res = await get<CommissionListResponse>(`/${fid}/commission`, { params: query });
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '查询提成失败', res.code);
  }
  return res.data;
}

export async function markCommissionPaid(id: string): Promise<Commission> {
  const fid = getFactoryId();
  const res = await put<Commission>(`/${fid}/commission/${id}/mark-paid`);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '标记发放失败', res.code);
  }
  return res.data;
}

export async function cancelCommission(id: string): Promise<Commission> {
  const fid = getFactoryId();
  const res = await put<Commission>(`/${fid}/commission/${id}/cancel`);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '取消失败', res.code);
  }
  return res.data;
}

// ==================== Commission Rule API ====================

export async function listRules(): Promise<CommissionRule[]> {
  const fid = getFactoryId();
  const res = await get<CommissionRule[]>(`/${fid}/commission/rules`);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '查询规则失败', res.code);
  }
  return res.data;
}

export async function createRule(body: {
  salesId?: number | null;
  customerType?: string | null;
  percentage: number;
  effectiveFrom: string;
  effectiveTo?: string | null;
  createdBy: number;
}): Promise<CommissionRule> {
  const fid = getFactoryId();
  const res = await post<CommissionRule>(`/${fid}/commission/rules`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '创建规则失败', res.code);
  }
  return res.data;
}

export async function updateRule(
  id: string,
  body: {
    percentage?: number;
    effectiveFrom?: string;
    effectiveTo?: string | null;
    active?: boolean;
  },
): Promise<CommissionRule> {
  const fid = getFactoryId();
  const res = await put<CommissionRule>(`/${fid}/commission/rules/${id}`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '更新规则失败', res.code);
  }
  return res.data;
}

export async function deleteRule(id: string): Promise<void> {
  const fid = getFactoryId();
  const res = await del(`/${fid}/commission/rules/${id}`);
  if (!res.success) {
    throw new ApiError(res.message || '删除规则失败', res.code);
  }
}

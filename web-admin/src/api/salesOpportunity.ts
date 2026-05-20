// Sprint 7 wave 2 Track T4 — 销售商机 API client.
// Backend: SalesOpportunityController @RequestMapping
//   "/api/mobile/{factoryId}/sales-opportunity"
//
// 防呆 R3 (stage dropdown): 8 个商机阶段 enum
// 防呆 R5 (kanban dead-end): 空 kanban 跳"新增商机" dialog

import { get, post, put, del } from './request';

/** 商机阶段 enum (mirrors backend OpportunityStage). */
export type OpportunityStage =
  | 'LEAD'
  | 'QUALIFIED'
  | 'DEMO'
  | 'PROPOSAL'
  | 'NEGOTIATE'
  | 'VERBAL'
  | 'CLOSED_WON'
  | 'CLOSED_LOST';

export interface StageMeta {
  value: OpportunityStage;
  label: string;
  /** 默认转化概率 (per stage). */
  probability: number;
  /** Element Plus tag type for visual cue. */
  tagType: 'info' | 'primary' | 'success' | 'warning' | 'danger';
  /** Whether terminal (CLOSED_WON / CLOSED_LOST). */
  terminal: boolean;
  /** Display order for kanban + funnel. */
  order: number;
  /** Hex color for funnel chart. */
  color: string;
}

export const STAGE_META: StageMeta[] = [
  { value: 'LEAD',        label: '线索',     probability: 10,  tagType: 'info',    terminal: false, order: 0, color: '#909399' },
  { value: 'QUALIFIED',   label: '已资格化', probability: 30,  tagType: 'info',    terminal: false, order: 1, color: '#67C23A' },
  { value: 'DEMO',        label: '产品演示', probability: 50,  tagType: 'primary', terminal: false, order: 2, color: '#409EFF' },
  { value: 'PROPOSAL',    label: '方案报价', probability: 70,  tagType: 'primary', terminal: false, order: 3, color: '#5470C6' },
  { value: 'NEGOTIATE',   label: '商务谈判', probability: 85,  tagType: 'warning', terminal: false, order: 4, color: '#E6A23C' },
  { value: 'VERBAL',      label: '口头承诺', probability: 95,  tagType: 'warning', terminal: false, order: 5, color: '#F56C6C' },
  { value: 'CLOSED_WON',  label: '赢单',     probability: 100, tagType: 'success', terminal: true,  order: 6, color: '#67C23A' },
  { value: 'CLOSED_LOST', label: '丢单',     probability: 0,   tagType: 'danger',  terminal: true,  order: 7, color: '#F56C6C' },
];

export function stageMeta(value?: OpportunityStage | string): StageMeta {
  return (
    STAGE_META.find((m) => m.value === value) ||
    STAGE_META[0]
  );
}

export function stageLabel(value?: OpportunityStage | string): string {
  if (!value) return '—';
  return stageMeta(value as OpportunityStage).label;
}

export interface SalesOpportunity {
  id: string;
  factoryId: string;
  customerId: string;
  title: string;
  stage: OpportunityStage;
  valueAmount?: number | null;
  probability: number;
  expectedCloseDate?: string | null;
  ownerId: number;
  closedAt?: string | null;
  description?: string | null;
  createdBy: number;
  version: number;
  createdAt?: string;
  updatedAt?: string;
  /** Optional joined relation fields (when backend hydrates). */
  customer?: { id: string; name: string; code?: string };
  owner?: { id: number; username?: string; realName?: string };
}

export interface OpportunityStageHistory {
  id: string;
  factoryId: string;
  opportunityId: string;
  fromStage: OpportunityStage | null;
  toStage: OpportunityStage;
  reason?: string;
  changedBy: number;
  changedAt: string;
  createdAt?: string;
}

export interface OpportunityPage {
  content: SalesOpportunity[];
  totalElements: number;
  totalPages: number;
  count: number;
}

export interface FunnelStatEntry {
  stage: OpportunityStage;
  stageDisplay: string;
  count: number;
  totalValue: number;
  expectedValue: number;
}

export interface CreateOpportunityRequest {
  customerId: string;
  title: string;
  stage?: OpportunityStage;
  valueAmount?: number | null;
  probability?: number | null;
  expectedCloseDate?: string | null;
  ownerId: number;
  description?: string;
  createdBy: number;
}

export interface UpdateOpportunityRequest {
  title?: string;
  valueAmount?: number | null;
  probability?: number | null;
  expectedCloseDate?: string | null;
  ownerId?: number;
  description?: string;
}

export interface TransitionStageRequest {
  newStage: OpportunityStage;
  reason?: string;
  confirmSkip?: boolean;
  probabilityOverride?: number;
  changedBy: number;
}

function basePath(factoryId: string): string {
  return `/${factoryId}/sales-opportunity`;
}

export async function listOpportunities(
  factoryId: string,
  params: {
    stage?: OpportunityStage | string;
    ownerId?: number;
    page?: number;
    size?: number;
  } = {},
): Promise<OpportunityPage> {
  const res = await get<OpportunityPage>(basePath(factoryId), { params });
  if (!res.success || !res.data) {
    return { content: [], totalElements: 0, totalPages: 0, count: 0 };
  }
  return res.data;
}

export async function getOpportunity(
  factoryId: string,
  id: string,
): Promise<SalesOpportunity> {
  const res = await get<SalesOpportunity>(`${basePath(factoryId)}/${id}`);
  if (!res.success || !res.data) {
    throw new Error(res.message || '加载商机失败');
  }
  return res.data;
}

export async function getOpportunityHistory(
  factoryId: string,
  id: string,
): Promise<OpportunityStageHistory[]> {
  const res = await get<OpportunityStageHistory[]>(
    `${basePath(factoryId)}/${id}/history`,
  );
  if (!res.success || !res.data) return [];
  return res.data;
}

export async function getFunnelStats(
  factoryId: string,
  ownerId?: number,
): Promise<FunnelStatEntry[]> {
  const params: Record<string, unknown> = {};
  if (ownerId != null) params.ownerId = ownerId;
  const res = await get<FunnelStatEntry[]>(
    `${basePath(factoryId)}/funnel-stats`,
    { params },
  );
  if (!res.success || !res.data) return [];
  return res.data;
}

export async function createOpportunity(
  factoryId: string,
  body: CreateOpportunityRequest,
): Promise<SalesOpportunity> {
  const res = await post<SalesOpportunity>(basePath(factoryId), body);
  if (!res.success || !res.data) {
    throw new Error(res.message || '创建商机失败');
  }
  return res.data;
}

export async function updateOpportunity(
  factoryId: string,
  id: string,
  body: UpdateOpportunityRequest,
): Promise<SalesOpportunity> {
  const res = await put<SalesOpportunity>(`${basePath(factoryId)}/${id}`, body);
  if (!res.success || !res.data) {
    throw new Error(res.message || '更新商机失败');
  }
  return res.data;
}

export async function transitionStage(
  factoryId: string,
  id: string,
  body: TransitionStageRequest,
): Promise<SalesOpportunity> {
  const res = await put<SalesOpportunity>(
    `${basePath(factoryId)}/${id}/transition`,
    body,
  );
  if (!res.success || !res.data) {
    throw new Error(res.message || '商机阶段变更失败');
  }
  return res.data;
}

export async function deleteOpportunity(
  factoryId: string,
  id: string,
): Promise<void> {
  const res = await del(`${basePath(factoryId)}/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除商机失败');
  }
}

/** Format BigDecimal value to currency display. */
export function formatCurrency(v?: number | string | null): string {
  if (v == null) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** Compute expected value (valueAmount × probability/100). */
export function expectedValue(opp: SalesOpportunity): number {
  const v = typeof opp.valueAmount === 'number' ? opp.valueAmount : 0;
  return Number(((v * opp.probability) / 100).toFixed(2));
}

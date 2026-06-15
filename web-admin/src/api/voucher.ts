// Sprint 7 T1 F-VOUCHER-2 Phase D: Voucher + Account API client (web-admin).
//
// Backend (Sprint 3-E + 5-F + 6 W4-A merged):
//   GET  /api/mobile/{factoryId}/finance/vouchers          (paged list)
//   GET  /api/mobile/{factoryId}/finance/vouchers/{id}     (detail w/ entries)
//   POST /api/mobile/{factoryId}/finance/vouchers/generate
//   POST /api/mobile/{factoryId}/finance/vouchers/batch-generate
//   POST /api/mobile/{factoryId}/finance/vouchers/{id}/post
//   POST /api/mobile/{factoryId}/finance/vouchers/{id}/void
//
// Sprint 7 T1 NEW (this PR):
//   GET    /api/mobile/{factoryId}/finance/accounts        (factory + system union)
//   GET    /api/mobile/{factoryId}/finance/accounts/tree   (grouped by category)
//   POST   /api/mobile/{factoryId}/finance/accounts
//   PUT    /api/mobile/{factoryId}/finance/accounts/{id}
//   DELETE /api/mobile/{factoryId}/finance/accounts/{id}
import { get, post, put, del } from './request';
import { ApiError } from '@/types/api';

// ==================== Voucher 类型 ====================

export type VoucherStatus = 'DRAFT' | 'POSTED' | 'VOID';
export type VoucherType =
  | 'SALES_RECEIPT'
  | 'PURCHASE_PAYMENT'
  | 'INVENTORY_TRANSFER'
  | 'EXPENSE'
  | 'WAGE'
  | 'RETURN'
  | 'DEPRECATION';

export type AuxiliaryType =
  | 'CUSTOMER'
  | 'SUPPLIER'
  | 'DEPT'
  | 'EMPLOYEE'
  | 'PROJECT'
  | 'INVENTORY'
  | 'OUTSOURCER';

export interface VoucherEntry {
  id: string;
  lineNo: number;
  subjectCode: string;
  subjectName: string;
  description?: string | null;
  debit?: number | null;
  credit?: number | null;
  costCenter?: string | null;
  auxiliaryType?: AuxiliaryType | null;
  auxiliaryEntityId?: string | null;
}

export interface Voucher {
  id: string;
  factoryId: string;
  voucherNumber: string;
  voucherType: VoucherType;
  voucherDate: string;  // ISO date "YYYY-MM-DD"
  sourceBusinessType: string;
  sourceBusinessId: string;
  totalDebit: number;
  totalCredit: number;
  status: VoucherStatus;
  createdBy?: number | null;
  approvedBy?: number | null;
  approvedAt?: string | null;
  description?: string | null;
  entries: VoucherEntry[];
  createdAt?: string;
  updatedAt?: string;
}

// ==================== Account 类型 ====================

export type AccountCategory =
  | 'ASSET'
  | 'LIABILITY'
  | 'EQUITY'
  | 'REVENUE'
  | 'EXPENSE'
  | 'COST';

export type AccountBalanceType = 'DEBIT_NORMAL' | 'CREDIT_NORMAL';

export interface Account {
  id: string;
  factoryId?: string | null;  // null = 系统标准科目
  code: string;
  name: string;
  parentId?: string | null;
  level: number;  // 1-4
  category: AccountCategory;
  balanceType: AccountBalanceType;
  description?: string | null;
  active: boolean;
  sortOrder: number;
  createdAt?: string;
  updatedAt?: string;
}

// ==================== Voucher API ====================

export async function getVoucherDetail(factoryId: string, voucherId: string): Promise<Voucher> {
  const res = await get<Voucher>(`/${factoryId}/finance/vouchers/${voucherId}`);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '凭证加载失败', res.code);
  }
  return res.data;
}

export async function postVoucher(factoryId: string, voucherId: string): Promise<Voucher> {
  const res = await post<Voucher>(`/${factoryId}/finance/vouchers/${voucherId}/post`, {});
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '过账失败', res.code);
  }
  return res.data;
}

export async function voidVoucher(
  factoryId: string,
  voucherId: string,
  reason: string,
): Promise<void> {
  const res = await post(`/${factoryId}/finance/vouchers/${voucherId}/void`, { reason });
  if (!res.success) {
    throw new ApiError(res.message || '作废失败', res.code);
  }
}

// ==================== Account API ====================

export async function listAccounts(
  factoryId: string,
  options: { activeOnly?: boolean; category?: AccountCategory } = {},
): Promise<Account[]> {
  const params = new URLSearchParams();
  if (options.activeOnly) params.set('activeOnly', 'true');
  if (options.category) params.set('category', options.category);
  const url = `/${factoryId}/finance/accounts${params.toString() ? '?' + params.toString() : ''}`;
  const res = await get<Account[]>(url);
  if (!res.success) {
    throw new ApiError(res.message || '科目加载失败', res.code);
  }
  return res.data ?? [];
}

export async function getAccountTree(
  factoryId: string,
): Promise<Record<AccountCategory, Account[]>> {
  const res = await get<Record<AccountCategory, Account[]>>(`/${factoryId}/finance/accounts/tree`);
  if (!res.success) {
    throw new ApiError(res.message || '科目树加载失败', res.code);
  }
  return res.data ?? ({} as Record<AccountCategory, Account[]>);
}

export async function getAccountDetail(factoryId: string, id: string): Promise<Account> {
  const res = await get<Account>(`/${factoryId}/finance/accounts/${id}`);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '科目不存在', res.code);
  }
  return res.data;
}

export async function createAccount(
  factoryId: string,
  body: Partial<Account>,
): Promise<Account> {
  const res = await post<Account>(`/${factoryId}/finance/accounts`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '创建科目失败', res.code);
  }
  return res.data;
}

export async function updateAccount(
  factoryId: string,
  id: string,
  body: Partial<Account>,
): Promise<Account> {
  const res = await put<Account>(`/${factoryId}/finance/accounts/${id}`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '更新科目失败', res.code);
  }
  return res.data;
}

export async function deleteAccount(factoryId: string, id: string): Promise<void> {
  const res = await del(`/${factoryId}/finance/accounts/${id}`);
  if (!res.success) {
    throw new ApiError(res.message || '删除科目失败', res.code);
  }
}

// ==================== Helpers ====================

/** 中文 label 字典 — UI display */
export const ACCOUNT_CATEGORY_LABEL: Record<AccountCategory, string> = {
  ASSET: '资产',
  LIABILITY: '负债',
  EQUITY: '所有者权益',
  REVENUE: '收入',
  EXPENSE: '费用',
  COST: '成本',
};

export const ACCOUNT_BALANCE_TYPE_LABEL: Record<AccountBalanceType, string> = {
  DEBIT_NORMAL: '借方余额',
  CREDIT_NORMAL: '贷方余额',
};

export const VOUCHER_STATUS_LABEL: Record<VoucherStatus, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  POSTED: { text: '已过账', type: 'success' },
  VOID: { text: '已作废', type: 'danger' },
};

export const VOUCHER_TYPE_LABEL: Record<VoucherType, string> = {
  SALES_RECEIPT: '销售收款',
  PURCHASE_PAYMENT: '采购付款',
  INVENTORY_TRANSFER: '库存调拨',
  EXPENSE: '报销',
  WAGE: '工资发放',
  RETURN: '退货',
  DEPRECATION: '折旧',
};

export const AUXILIARY_TYPE_LABEL: Record<AuxiliaryType, string> = {
  CUSTOMER: '客户',
  SUPPLIER: '供应商',
  DEPT: '部门',
  EMPLOYEE: '职员',
  PROJECT: '项目',
  INVENTORY: '存货',
  OUTSOURCER: '委外商',
};

/** 计算 sum(debit) — number-safe (null → 0). */
export function sumDebit(entries: VoucherEntry[]): number {
  return entries.reduce((acc, e) => acc + Number(e.debit ?? 0), 0);
}

export function sumCredit(entries: VoucherEntry[]): number {
  return entries.reduce((acc, e) => acc + Number(e.credit ?? 0), 0);
}

/** 平衡检查 — Math.abs < 0.001 fix float precision (Decimal scale-2 → 0.005 safe). */
export function isBalanced(entries: VoucherEntry[]): boolean {
  const debit = sumDebit(entries);
  const credit = sumCredit(entries);
  return Math.abs(debit - credit) < 0.001;
}

// ==================== Voucher List API ====================

export interface VoucherListParams {
  status?: VoucherStatus;
  type?: VoucherType;
  page?: number;
  size?: number;
}

export interface PagedResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export async function listVouchers(
  factoryId: string,
  params: VoucherListParams = {},
): Promise<PagedResult<Voucher>> {
  const query = new URLSearchParams();
  if (params.status) query.set('status', params.status);
  if (params.type) query.set('type', params.type);
  query.set('page', String(params.page ?? 1));
  query.set('size', String(params.size ?? 20));
  const res = await get<PagedResult<Voucher>>(
    `/${factoryId}/finance/vouchers?${query.toString()}`,
  );
  if (!res.success) {
    throw new ApiError(res.message || '凭证列表加载失败', res.code);
  }
  return res.data ?? { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
}

export async function generateVoucher(
  factoryId: string,
  businessType: string,
  businessId: string,
): Promise<Voucher> {
  const res = await post<Voucher>(`/${factoryId}/finance/vouchers/generate`, {
    businessType,
    businessId,
  });
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '生成凭证失败', res.code);
  }
  return res.data;
}

// ==================== VoucherTemplate 类型 ====================

export type TemplateEntryDirection = 'DEBIT' | 'CREDIT';

export interface TemplateEntry {
  sortOrder?: number | null;
  subjectCode: string;
  subjectName: string;
  direction: TemplateEntryDirection;
  amountExpression?: string | null;
  description?: string | null;
  omitWhenZero?: boolean | null;
}

export interface VoucherTemplate {
  id: string;
  factoryId: string;
  voucherType: VoucherType;
  name: string;
  description?: string | null;
  entries: TemplateEntry[];
  isDefault: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

// ==================== VoucherTemplate API ====================

export async function listVoucherTemplates(factoryId: string): Promise<VoucherTemplate[]> {
  const res = await get<VoucherTemplate[]>(`/${factoryId}/voucher-templates`);
  if (!res.success) {
    throw new ApiError(res.message || '凭证模板加载失败', res.code);
  }
  return res.data ?? [];
}

export async function listVoucherTemplatesByType(
  factoryId: string,
  voucherType: VoucherType,
): Promise<VoucherTemplate[]> {
  const res = await get<VoucherTemplate[]>(
    `/${factoryId}/voucher-templates/by-type/${voucherType}`,
  );
  if (!res.success) {
    throw new ApiError(res.message || '凭证模板加载失败', res.code);
  }
  return res.data ?? [];
}

export async function createVoucherTemplate(
  factoryId: string,
  body: Partial<VoucherTemplate>,
): Promise<VoucherTemplate> {
  const res = await post<VoucherTemplate>(`/${factoryId}/voucher-templates`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '创建凭证模板失败', res.code);
  }
  return res.data;
}

export async function updateVoucherTemplate(
  factoryId: string,
  id: string,
  body: Partial<VoucherTemplate>,
): Promise<VoucherTemplate> {
  const res = await put<VoucherTemplate>(`/${factoryId}/voucher-templates/${id}`, body);
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '更新凭证模板失败', res.code);
  }
  return res.data;
}

export async function deleteVoucherTemplate(factoryId: string, id: string): Promise<void> {
  const res = await del(`/${factoryId}/voucher-templates/${id}`);
  if (!res.success) {
    throw new ApiError(res.message || '删除凭证模板失败', res.code);
  }
}

export async function setVoucherTemplateDefault(
  factoryId: string,
  id: string,
): Promise<VoucherTemplate> {
  const res = await post<VoucherTemplate>(
    `/${factoryId}/voucher-templates/${id}/set-default`,
    {},
  );
  if (!res.success || !res.data) {
    throw new ApiError(res.message || '设置默认模板失败', res.code);
  }
  return res.data;
}

/** 中文 label — TemplateEntry direction */
export const DIRECTION_LABEL: Record<TemplateEntryDirection, string> = {
  DEBIT: '借方',
  CREDIT: '贷方',
};

import type { AxiosRequestConfig, AxiosResponse } from 'axios';
import request from '@/api/request';

export type SupplierStatus = 'ACTIVE' | 'INACTIVE';
export type SupplierImportMode = 'STANDARD' | 'SMART';
export type SupplierImportClassification = 'VALID' | 'DUPLICATE' | 'ERROR' | 'IGNORED';

export interface SupplierRecord {
  id: string;
  factoryId?: string;
  supplierCode?: string;
  code?: string;
  name: string;
  /** 供应商简称（可空）。同一工厂内非空简称不区分大小写唯一。 */
  shortName?: string | null;
  /** 后端算好的展示名 = 简称 ?? 全称。下拉/列表一律用它，别在前端各拼一次。 */
  displayName?: string | null;
  contactPerson?: string | null;
  phone?: string | null;
  contactPhone?: string | null;
  email?: string | null;
  address?: string | null;
  bankAccount?: string | null;
  taxNumber?: string | null;
  taxId?: string | null;
  notes?: string | null;
  status?: SupplierStatus | string | null;
  isActive?: boolean | null;
  profileComplete?: boolean | null;
  totalOrders?: number | null;
  totalAmount?: number | null;
  currentBalance?: number | null;
  lastOrderDate?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  createdByName?: string | null;
  version?: number | null;
}

export interface SupplierSavePayload {
  name: string;
  shortName?: string;
  contactPerson: string;
  phone: string;
  address: string;
  email?: string;
  bankAccount?: string;
  taxNumber?: string;
  notes?: string;
  version?: number;
}

export interface SupplierPage {
  content: SupplierRecord[];
  totalElements?: number;
}

// ─────────────────── 多联系人 / 多地址 / 多银行账户 ───────────────────

export type SupplierContactType =
  'OWNER' | 'SALES' | 'FINANCE' | 'LOGISTICS' | 'AFTER_SALES' | 'OTHER';

export type SupplierAddressType =
  'BUSINESS' | 'SHIPPING' | 'BILLING' | 'WAREHOUSE' | 'OTHER';

/** 与后端 SupplierContactType 枚举一一对应；改一边必须改另一边。 */
export const SUPPLIER_CONTACT_TYPE_OPTIONS: Array<{ value: SupplierContactType; label: string }> = [
  { value: 'OWNER', label: '负责人' },
  { value: 'SALES', label: '业务对接' },
  { value: 'FINANCE', label: '财务对账' },
  { value: 'LOGISTICS', label: '送货/物流' },
  { value: 'AFTER_SALES', label: '售后' },
  { value: 'OTHER', label: '其他' },
];

/** 与后端 SupplierAddressType 枚举一一对应。 */
export const SUPPLIER_ADDRESS_TYPE_OPTIONS: Array<{ value: SupplierAddressType; label: string }> = [
  { value: 'BUSINESS', label: '注册/办公地址' },
  { value: 'SHIPPING', label: '发货地址' },
  { value: 'BILLING', label: '开票地址' },
  { value: 'WAREHOUSE', label: '仓库地址' },
  { value: 'OTHER', label: '其他' },
];

export interface SupplierContact {
  id?: string | null;
  supplierId?: string | null;
  name: string;
  contactType?: SupplierContactType | null;
  contactTypeLabel?: string | null;
  phone?: string | null;
  email?: string | null;
  position?: string | null;
  isPrimary?: boolean | null;
  sortOrder?: number | null;
  notes?: string | null;
  version?: number | null;
}

export interface SupplierAddress {
  id?: string | null;
  supplierId?: string | null;
  label?: string | null;
  addressType?: SupplierAddressType | null;
  addressTypeLabel?: string | null;
  address: string;
  contactName?: string | null;
  contactPhone?: string | null;
  isPrimary?: boolean | null;
  sortOrder?: number | null;
  notes?: string | null;
  version?: number | null;
}

export interface SupplierBankAccount {
  id?: string | null;
  supplierId?: string | null;
  accountName?: string | null;
  bankName: string;
  branchName?: string | null;
  accountNumber: string;
  currency?: string | null;
  isPrimary?: boolean | null;
  sortOrder?: number | null;
  notes?: string | null;
  version?: number | null;
}

export interface SupplierImportMapping {
  sourceColumn: string;
  targetField: string | null;
  confidence?: number | null;
  required?: boolean;
}

export interface SupplierImportRow {
  rowNumber: number;
  classification: SupplierImportClassification;
  selected?: boolean;
  data?: Partial<SupplierSavePayload> & { supplierCode?: string; status?: string };
  errors?: Record<string, string>;
}

export interface SupplierImportPreview {
  fileDigest: string;
  mode: SupplierImportMode;
  mappings: SupplierImportMapping[];
  rows: SupplierImportRow[];
  counts: Record<string, number>;
}

export interface SupplierImportConfirmRequest {
  fileDigest: string;
  idempotencyKey: string;
  rows: Array<NonNullable<SupplierImportRow['data']>>;
}

export interface SupplierImportResult {
  createdCount: number;
  skippedCount: number;
  failedCount: number;
  replayed?: boolean;
}

export interface SupplierMaterialRelation {
  id: string;
  supplierId?: string;
  supplierName?: string | null;
  materialTypeId: string;
  materialName?: string | null;
  materialCode?: string | null;
  baseUnit?: string | null;
  supplierMaterialCode?: string | null;
  defaultPurchasePrice?: number | null;
  materialReferencePrice?: number | null;
  materialReferencePriceUnit?: string | null;
  effectivePurchasePrice?: number | null;
  effectivePriceUnit?: string | null;
  priceSource?: string | null;
  currency?: string | null;
  purchaseUnit?: string | null;
  minOrderQuantity?: number | null;
  leadTimeDays?: number | null;
  preferred?: boolean | null;
  active?: boolean | null;
  version?: number | null;
}

export interface SupplierMaterialPayload {
  materialTypeId: string;
  supplierMaterialCode?: string;
  defaultPurchasePrice?: number | null;
  currency: string;
  purchaseUnit: string;
  minOrderQuantity?: number | null;
  leadTimeDays?: number | null;
  preferred: boolean;
  active: boolean;
  version?: number;
}

export interface SupplierPurchaseSpec {
  id: string;
  supplierMaterialId: string;
  materialTypeId: string;
  name: string;
  purchasePackageUnit: string;
  inventoryBaseUnit: string;
  factor: number;
  quotedPrice?: number | null;
  currency?: string | null;
  minOrderQuantity?: number | null;
  leadTimeDays?: number | null;
  defaultSpec?: boolean | null;
  active?: boolean | null;
  version?: number | null;
}

export interface SupplierPurchaseSpecPayload {
  name: string;
  purchasePackageUnit: string;
  inventoryBaseUnit: string;
  factor: number;
  quotedPrice?: number | null;
  currency: string;
  minOrderQuantity?: number | null;
  leadTimeDays?: number | null;
  defaultSpec: boolean;
  active: boolean;
  version?: number;
}

type BlobRequestConfig = AxiosRequestConfig & { _keepResponse: true };

function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

export async function listSuppliers(factoryId: string): Promise<SupplierRecord[]> {
  const response = await request.get<SupplierPage>(`/${factoryId}/suppliers`, {
    params: { page: 1, size: 500 },
  });
  return response.data?.content ?? [];
}

export async function listActiveSuppliers(factoryId: string): Promise<SupplierRecord[]> {
  const response = await request.get<SupplierRecord[]>(`/${factoryId}/suppliers/active`);
  return response.data ?? [];
}

export async function getSupplier(factoryId: string, supplierId: string): Promise<SupplierRecord> {
  const response = await request.get<SupplierRecord>(`/${factoryId}/suppliers/${supplierId}`);
  return response.data;
}

export async function createSupplier(
  factoryId: string,
  payload: SupplierSavePayload,
): Promise<SupplierRecord> {
  const response = await request.post<SupplierRecord>(`/${factoryId}/suppliers`, payload);
  return response.data;
}

export async function updateSupplier(
  factoryId: string,
  supplierId: string,
  payload: SupplierSavePayload,
): Promise<SupplierRecord> {
  const response = await request.put<SupplierRecord>(`/${factoryId}/suppliers/${supplierId}`, payload);
  return response.data;
}

export async function updateSupplierStatus(
  factoryId: string,
  supplier: Pick<SupplierRecord, 'id' | 'version'>,
  active: boolean,
  reason: string,
): Promise<SupplierRecord> {
  const response = await request.put<SupplierRecord>(
    `/${factoryId}/suppliers/${supplier.id}/status`,
    { isActive: active, reason: reason.trim(), version: supplier.version },
  );
  return response.data;
}

// ─────────────────── 多联系人 / 多地址 / 多银行账户 API ───────────────────
// 写接口一律返回「保存后的完整列表」—— 主标记是后端重算的（第一条自动置主、
// 删主自动顺位提升），前端拿单条回包自己 patch 数组必然与后端不一致。

export async function listSupplierContacts(
  factoryId: string, supplierId: string,
): Promise<SupplierContact[]> {
  const response = await request.get<SupplierContact[]>(
    `/${factoryId}/suppliers/${supplierId}/contacts`);
  return response.data ?? [];
}

export async function saveSupplierContact(
  factoryId: string, supplierId: string, payload: SupplierContact,
): Promise<SupplierContact[]> {
  const response = await request.post<SupplierContact[]>(
    `/${factoryId}/suppliers/${supplierId}/contacts`, payload);
  return response.data ?? [];
}

export async function deleteSupplierContact(
  factoryId: string, supplierId: string, contactId: string,
): Promise<SupplierContact[]> {
  const response = await request.delete<SupplierContact[]>(
    `/${factoryId}/suppliers/${supplierId}/contacts/${contactId}`);
  return response.data ?? [];
}

export async function listSupplierAddresses(
  factoryId: string, supplierId: string,
): Promise<SupplierAddress[]> {
  const response = await request.get<SupplierAddress[]>(
    `/${factoryId}/suppliers/${supplierId}/addresses`);
  return response.data ?? [];
}

export async function saveSupplierAddress(
  factoryId: string, supplierId: string, payload: SupplierAddress,
): Promise<SupplierAddress[]> {
  const response = await request.post<SupplierAddress[]>(
    `/${factoryId}/suppliers/${supplierId}/addresses`, payload);
  return response.data ?? [];
}

export async function deleteSupplierAddress(
  factoryId: string, supplierId: string, addressId: string,
): Promise<SupplierAddress[]> {
  const response = await request.delete<SupplierAddress[]>(
    `/${factoryId}/suppliers/${supplierId}/addresses/${addressId}`);
  return response.data ?? [];
}

export async function listSupplierBankAccounts(
  factoryId: string, supplierId: string,
): Promise<SupplierBankAccount[]> {
  const response = await request.get<SupplierBankAccount[]>(
    `/${factoryId}/suppliers/${supplierId}/bank-accounts`);
  return response.data ?? [];
}

export async function saveSupplierBankAccount(
  factoryId: string, supplierId: string, payload: SupplierBankAccount,
): Promise<SupplierBankAccount[]> {
  const response = await request.post<SupplierBankAccount[]>(
    `/${factoryId}/suppliers/${supplierId}/bank-accounts`, payload);
  return response.data ?? [];
}

export async function deleteSupplierBankAccount(
  factoryId: string, supplierId: string, bankAccountId: string,
): Promise<SupplierBankAccount[]> {
  const response = await request.delete<SupplierBankAccount[]>(
    `/${factoryId}/suppliers/${supplierId}/bank-accounts/${bankAccountId}`);
  return response.data ?? [];
}

export async function downloadSupplierTemplate(factoryId: string): Promise<void> {
  const config: BlobRequestConfig = { responseType: 'blob', _keepResponse: true };
  const response = await request.get(
    `/${factoryId}/suppliers/import/template`,
    config,
  ) as unknown as AxiosResponse<Blob>;
  const blob = response.data instanceof Blob ? response.data : new Blob([response.data]);
  downloadBlob(blob, '供应商导入标准模板.xlsx');
}

export async function previewSupplierImport(
  factoryId: string,
  file: File,
  mode: SupplierImportMode,
  columnMapping: Record<string, string>,
): Promise<SupplierImportPreview> {
  const form = new FormData();
  form.append('file', file);
  form.append('mode', mode);
  form.append('columnMappingJson', JSON.stringify(columnMapping));
  const response = await request.post<SupplierImportPreview>(
    `/${factoryId}/suppliers/import/preview`,
    form,
  );
  return response.data;
}

export async function confirmSupplierImport(
  factoryId: string,
  payload: SupplierImportConfirmRequest,
): Promise<SupplierImportResult> {
  const response = await request.post<SupplierImportResult>(
    `/${factoryId}/suppliers/import/confirm`,
    payload,
  );
  return response.data;
}

export async function downloadSupplierImportErrors(
  factoryId: string,
  preview: SupplierImportPreview,
): Promise<void> {
  const config: BlobRequestConfig = { responseType: 'blob', _keepResponse: true };
  const response = await request.post(
    `/${factoryId}/suppliers/import/error-report`,
    preview.rows.filter(
      (row) => row.classification !== 'VALID' && row.classification !== 'IGNORED',
    ),
    config,
  ) as unknown as AxiosResponse<Blob>;
  const blob = response.data instanceof Blob ? response.data : new Blob([response.data]);
  downloadBlob(blob, '供应商导入错误报告.xlsx');
}

export async function listSupplierMaterials(
  factoryId: string,
  supplierId: string,
): Promise<SupplierMaterialRelation[]> {
  const response = await request.get<SupplierMaterialRelation[]>(
    `/${factoryId}/suppliers/${supplierId}/materials`,
  );
  return response.data ?? [];
}

export async function listMaterialSuppliers(
  factoryId: string,
  materialTypeId: string,
): Promise<SupplierMaterialRelation[]> {
  const response = await request.get<SupplierMaterialRelation[]>(
    `/${factoryId}/materials/${materialTypeId}/suppliers`,
  );
  return response.data ?? [];
}

export async function createSupplierMaterial(
  factoryId: string,
  supplierId: string,
  payload: SupplierMaterialPayload,
): Promise<SupplierMaterialRelation> {
  const response = await request.post<SupplierMaterialRelation>(
    `/${factoryId}/suppliers/${supplierId}/materials`,
    payload,
  );
  return response.data;
}

export async function updateSupplierMaterial(
  factoryId: string,
  supplierId: string,
  relationId: string,
  payload: SupplierMaterialPayload,
): Promise<SupplierMaterialRelation> {
  const response = await request.put<SupplierMaterialRelation>(
    `/${factoryId}/suppliers/${supplierId}/materials/${relationId}`,
    payload,
  );
  return response.data;
}

export async function deleteSupplierMaterial(
  factoryId: string,
  supplierId: string,
  relationId: string,
  version?: number | null,
): Promise<void> {
  await request.delete(`/${factoryId}/suppliers/${supplierId}/materials/${relationId}`, {
    params: version == null ? undefined : { version },
  });
}

function supplierPurchaseSpecsPath(factoryId: string, supplierId: string, relationId: string): string {
  return `/${factoryId}/suppliers/${supplierId}/materials/${relationId}/purchase-specs`;
}

export async function listSupplierPurchaseSpecs(
  factoryId: string,
  supplierId: string,
  relationId: string,
): Promise<SupplierPurchaseSpec[]> {
  const response = await request.get<SupplierPurchaseSpec[]>(
    supplierPurchaseSpecsPath(factoryId, supplierId, relationId),
  );
  return response.data ?? [];
}

export async function createSupplierPurchaseSpec(
  factoryId: string,
  supplierId: string,
  relationId: string,
  payload: SupplierPurchaseSpecPayload,
): Promise<SupplierPurchaseSpec> {
  const response = await request.post<SupplierPurchaseSpec>(
    supplierPurchaseSpecsPath(factoryId, supplierId, relationId), payload,
  );
  return response.data;
}

export async function updateSupplierPurchaseSpec(
  factoryId: string,
  supplierId: string,
  relationId: string,
  specId: string,
  payload: SupplierPurchaseSpecPayload,
): Promise<SupplierPurchaseSpec> {
  const response = await request.put<SupplierPurchaseSpec>(
    `${supplierPurchaseSpecsPath(factoryId, supplierId, relationId)}/${specId}`, payload,
  );
  return response.data;
}

export async function deleteSupplierPurchaseSpec(
  factoryId: string,
  supplierId: string,
  relationId: string,
  specId: string,
  version?: number | null,
): Promise<void> {
  await request.delete(`${supplierPurchaseSpecsPath(factoryId, supplierId, relationId)}/${specId}`, {
    params: version == null ? undefined : { version },
  });
}

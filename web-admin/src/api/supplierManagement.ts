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

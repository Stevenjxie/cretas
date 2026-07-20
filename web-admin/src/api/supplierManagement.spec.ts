import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('@/api/request', () => ({ default: requestMock }));

import {
  createSupplierMaterial,
  createSupplierPurchaseSpec,
  downloadSupplierImportErrors,
  listSupplierMaterials,
  listSupplierPurchaseSpecs,
  previewSupplierImport,
  updateSupplierStatus,
} from './supplierManagement';

describe('supplier management API boundary', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the query-only preview endpoint before any import confirmation write', async () => {
    requestMock.post.mockResolvedValueOnce({ data: {
      fileDigest: 'abc', mode: 'SMART', mappings: [], rows: [], counts: { TOTAL: 0 },
    } });
    const file = new File(['excel'], 'suppliers.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });

    await previewSupplierImport('F006', file, 'SMART', { 供货商: 'name' });

    expect(requestMock.post).toHaveBeenCalledTimes(1);
    expect(requestMock.post).toHaveBeenCalledWith(
      '/F006/suppliers/import/preview',
      expect.any(FormData),
    );
    expect(requestMock.put).not.toHaveBeenCalled();
    expect(requestMock.delete).not.toHaveBeenCalled();
  });

  it('sends the auditable JSON status contract with isActive, reason and version', async () => {
    requestMock.put.mockResolvedValueOnce({ data: { id: 'S1', name: '供应商', isActive: false } });
    await updateSupplierStatus('F006', { id: 'S1', version: 7 }, false, '  品质整改  ');
    expect(requestMock.put).toHaveBeenCalledWith('/F006/suppliers/S1/status', {
      isActive: false,
      reason: '品质整改',
      version: 7,
    });
  });

  it('uses the independent supplier-material relation endpoints', async () => {
    requestMock.get.mockResolvedValueOnce({ data: [{ id: 'R1', materialTypeId: 'M1' }] });
    requestMock.post.mockResolvedValueOnce({ data: { id: 'R2', materialTypeId: 'M2' } });

    await listSupplierMaterials('F006', 'S1');
    await createSupplierMaterial('F006', 'S1', {
      materialTypeId: 'M2',
      supplierMaterialCode: 'SUP-M2',
      currency: 'CNY',
      purchaseUnit: 'kg',
      preferred: true,
      active: true,
    });

    expect(requestMock.get).toHaveBeenCalledWith('/F006/suppliers/S1/materials');
    expect(requestMock.post).toHaveBeenCalledWith('/F006/suppliers/S1/materials', expect.objectContaining({
      materialTypeId: 'M2', supplierMaterialCode: 'SUP-M2', preferred: true,
    }));
  });

  it('uses relation-scoped purchase-spec endpoints and sends the authoritative base unit', async () => {
    requestMock.get.mockResolvedValueOnce({ data: [{ id: 'P1', supplierMaterialId: 'R1' }] });
    requestMock.post.mockResolvedValueOnce({ data: { id: 'P2', supplierMaterialId: 'R1' } });

    await listSupplierPurchaseSpecs('F006', 'S1', 'R1');
    await createSupplierPurchaseSpec('F006', 'S1', 'R1', {
      name: '10kg/箱',
      purchasePackageUnit: 'case',
      inventoryBaseUnit: 'kg',
      factor: 10,
      currency: 'CNY',
      defaultSpec: true,
      active: true,
    });

    const endpoint = '/F006/suppliers/S1/materials/R1/purchase-specs';
    expect(requestMock.get).toHaveBeenCalledWith(endpoint);
    expect(requestMock.post).toHaveBeenCalledWith(endpoint, expect.objectContaining({
      purchasePackageUnit: 'case', inventoryBaseUnit: 'kg', factor: 10,
    }));
  });

  it('sends only actionable preview rows as the direct error-report payload', async () => {
    requestMock.post.mockResolvedValueOnce({ data: new Blob(['report']) });

    await downloadSupplierImportErrors('F006', {
      fileDigest: 'digest',
      mode: 'STANDARD',
      mappings: [],
      counts: { total: 4, valid: 1, duplicate: 1, error: 1, ignored: 1 },
      rows: [
        { rowNumber: 2, classification: 'VALID', data: { name: 'A' }, errors: {} },
        { rowNumber: 3, classification: 'DUPLICATE', data: { name: 'B' }, errors: { name: '重复' } },
        { rowNumber: 4, classification: 'ERROR', data: { name: '' }, errors: { name: '必填' } },
        { rowNumber: 5, classification: 'IGNORED', data: null, errors: {} },
      ],
    });

    expect(requestMock.post).toHaveBeenCalledWith(
      '/F006/suppliers/import/error-report',
      [
        expect.objectContaining({ rowNumber: 3, classification: 'DUPLICATE' }),
        expect.objectContaining({ rowNumber: 4, classification: 'ERROR' }),
      ],
      expect.objectContaining({ responseType: 'blob', _keepResponse: true }),
    );
  });
});

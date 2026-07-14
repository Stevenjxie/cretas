import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../request', () => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

import { del, get, post, put } from '../request';
import {
  convertUnit,
  createProductUnitConversion,
  deleteProductUnitConversion,
  getUnitCatalog,
  listProductUnitConversions,
  updateProductUnitConversion,
  type ProductUnitConversion,
  type UnitConversionRequest,
} from '../unitContract';

const factoryId = 'F006';
const productTypeId = 'PT-1';
const conversionId = 'UC-1';
const conversionsBase = `/${factoryId}/product-types/${productTypeId}/unit-conversions`;

const conversion: ProductUnitConversion = {
  id: conversionId,
  productTypeId,
  fromUnitCode: 'box',
  fromUnitLabel: '盒',
  fromDimension: 'PACKAGE',
  toUnitCode: 'g',
  toUnitLabel: '克',
  toDimension: 'MASS',
  factor: 500,
  sourceType: 'MANUAL',
  primarySalesConversion: true,
  effectiveFrom: null,
  effectiveTo: null,
  version: 3,
};

beforeEach(() => {
  vi.mocked(get).mockReset().mockResolvedValue({ success: true, data: [], message: 'OK' });
  vi.mocked(post).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
  vi.mocked(put).mockReset().mockResolvedValue({ success: true, data: {}, message: 'OK' });
  vi.mocked(del).mockReset().mockResolvedValue({ success: true, data: null, message: 'OK' });
});

describe('unitContract API client', () => {
  it('gets the catalog and preserves the shared response envelope', async () => {
    const response = { success: true, data: [{ code: 'g', label: '克', dimension: 'MASS' as const, baseCode: 'g', displayScale: 3 }], message: 'OK' };
    vi.mocked(get).mockResolvedValueOnce(response);

    await expect(getUnitCatalog(factoryId)).resolves.toEqual(response);
    expect(get).toHaveBeenCalledWith(`/${factoryId}/units/catalog`);
  });

  it('posts the conversion request payload unchanged', async () => {
    const request: UnitConversionRequest = {
      quantity: 2,
      productTypeId,
      fromUnit: 'box',
      toUnit: 'g',
      at: '2026-07-14T10:00:00',
      scene: 'PRODUCTION',
      scale: 3,
      roundingMode: 'HALF_UP',
    };

    await convertUnit(factoryId, request);
    expect(post).toHaveBeenCalledWith(`/${factoryId}/units/convert`, request);
  });

  it('lists product unit conversions', async () => {
    await listProductUnitConversions(factoryId, productTypeId);
    expect(get).toHaveBeenCalledWith(conversionsBase);
  });

  it('creates a product unit conversion with the supplied payload', async () => {
    await createProductUnitConversion(factoryId, productTypeId, conversion);
    expect(post).toHaveBeenCalledWith(conversionsBase, conversion);
  });

  it('updates a product unit conversion with the supplied payload', async () => {
    await updateProductUnitConversion(factoryId, productTypeId, conversionId, conversion);
    expect(put).toHaveBeenCalledWith(`${conversionsBase}/${conversionId}`, conversion);
  });

  it('deletes a product unit conversion using its optimistic-lock version', async () => {
    await deleteProductUnitConversion(factoryId, productTypeId, conversionId, 3);
    expect(del).toHaveBeenCalledWith(`${conversionsBase}/${conversionId}`, { params: { version: 3 } });
  });
});

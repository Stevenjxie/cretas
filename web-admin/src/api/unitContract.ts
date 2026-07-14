import { del, get, post, put } from './request';

export type UnitDimension = 'MASS' | 'VOLUME' | 'COUNT' | 'PACKAGE';

export interface UnitCatalogItem {
  code: string;
  label: string;
  dimension: UnitDimension;
  baseCode: string;
  displayScale: number;
}

export type UnitConversionStatus =
  | 'IDENTITY'
  | 'CONVERTED'
  | 'UNKNOWN_UNIT'
  | 'PRODUCT_CONVERSION_MISSING'
  | 'AMBIGUOUS_CONVERSION';

export type UnitUsageScene = 'PROCUREMENT' | 'INVENTORY' | 'PRODUCTION' | 'SALES' | 'QUALITY';

export type UnitRoundingMode =
  | 'UP'
  | 'DOWN'
  | 'CEILING'
  | 'FLOOR'
  | 'HALF_UP'
  | 'HALF_DOWN'
  | 'HALF_EVEN'
  | 'UNNECESSARY';

export interface UnitConversionRequest {
  quantity: number | null;
  productTypeId?: string | null;
  fromUnit: string;
  toUnit: string;
  at?: string | null;
  scene?: UnitUsageScene | null;
  scale?: number | null;
  roundingMode?: UnitRoundingMode | null;
}

export interface UnitConversionStep {
  fromUnit: string;
  toUnit: string;
  factor: number;
  conversionRefId: string | null;
  conversionVersion: number | null;
}

export interface UnitConversionResult {
  status: UnitConversionStatus;
  quantity: number | null;
  fromUnit: string;
  toUnit: string;
  path: string[];
  conversionRefId: string | null;
  conversionVersion: number | null;
  message: string | null;
  steps: UnitConversionStep[];
}

export interface ProductUnitConversion {
  id?: string | null;
  productTypeId?: string | null;
  fromUnitCode: string;
  fromUnitLabel?: string | null;
  fromDimension?: UnitDimension | null;
  toUnitCode: string;
  toUnitLabel?: string | null;
  toDimension?: UnitDimension | null;
  factor: number;
  sourceType: 'MANUAL' | 'NET_CONTENT' | 'PACKAGING';
  primarySalesConversion?: boolean | null;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
  version?: number | null;
}

export function getUnitCatalog(factoryId: string) {
  return get<UnitCatalogItem[]>(`/${factoryId}/units/catalog`);
}

export function convertUnit(factoryId: string, payload: UnitConversionRequest) {
  return post<UnitConversionResult>(`/${factoryId}/units/convert`, payload);
}

export function listProductUnitConversions(factoryId: string, productTypeId: string) {
  return get<ProductUnitConversion[]>(
    `/${factoryId}/product-types/${productTypeId}/unit-conversions`,
  );
}

export function createProductUnitConversion(
  factoryId: string,
  productTypeId: string,
  payload: ProductUnitConversion,
) {
  return post<ProductUnitConversion>(
    `/${factoryId}/product-types/${productTypeId}/unit-conversions`,
    payload,
  );
}

export function updateProductUnitConversion(
  factoryId: string,
  productTypeId: string,
  id: string,
  payload: ProductUnitConversion,
) {
  return put<ProductUnitConversion>(
    `/${factoryId}/product-types/${productTypeId}/unit-conversions/${id}`,
    payload,
  );
}

export function deleteProductUnitConversion(
  factoryId: string,
  productTypeId: string,
  id: string,
  version: number,
) {
  return del<void>(
    `/${factoryId}/product-types/${productTypeId}/unit-conversions/${id}`,
    { params: { version } },
  );
}

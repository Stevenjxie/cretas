import { describe, expect, it } from 'vitest';
import { classifyOutputSkuCategory } from '../outputSkuClassification';

describe('workflow output SKU classification', () => {
  it.each([
    ['SEMI_FINISHED', 'SEMI_FINISHED'],
    ['FINISHED_PRODUCT', 'FINISHED_GOOD'],
    ['CONTRACT_MANUFACTURING', 'FINISHED_GOOD'],
    ['CUSTOMER_MATERIAL', 'FINISHED_GOOD'],
    ['DISH', 'FINISHED_GOOD'],
    ['COMBO', 'FINISHED_GOOD'],
  ] as const)('maps %s to %s', (productCategory, expectedKind) => {
    expect(classifyOutputSkuCategory(productCategory)).toBe(expectedKind);
  });

  it.each([
    'RAW_MATERIAL',
    'PACKAGING',
    'SEASONING',
    'ADD_ON',
    undefined,
    'UNKNOWN_CATEGORY',
  ])('rejects %s as a workflow output category', (productCategory) => {
    expect(classifyOutputSkuCategory(productCategory)).toBeNull();
  });
});

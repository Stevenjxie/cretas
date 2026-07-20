import { describe, expect, it } from 'vitest';
import {
  canonicalSalesOrderItemPayload,
  packagingOptionsForUnit,
  packagingSelectionError,
} from '../salesOrderUnitContract';

const caseToBox = {
  id: 'spec-case-box',
  packageUnit: '箱',
  baseUnit: '盒',
  conversionFactor: 8,
  active: true,
};

describe('sales order unit and packaging contract', () => {
  it('submits a Chinese display box order as canonical box without an outer-case spec', () => {
    const line = {
      productTypeId: 'SKU-BOX',
      quantity: 5,
      unit: '盒',
      boxQuantity: 0.63,
      packagingSpecId: '',
      packagingSpecs: [caseToBox],
    };

    expect(packagingOptionsForUnit(line)).toEqual([]);
    expect(canonicalSalesOrderItemPayload(line)).toMatchObject({
      productTypeId: 'SKU-BOX',
      quantity: 5,
      unit: 'box',
      boxQuantity: 0.63,
    });
    expect(canonicalSalesOrderItemPayload(line)).not.toHaveProperty('packagingSpecId');
    expect(canonicalSalesOrderItemPayload(line)).not.toHaveProperty('packagingSpecs');
  });

  it('retains the selected spec only when ordering by its canonical case unit', () => {
    const line = {
      productTypeId: 'SKU-BOX',
      quantity: 1,
      unit: 'case',
      packagingSpecId: caseToBox.id,
      packagingSpecs: [caseToBox],
    };

    expect(packagingOptionsForUnit(line)).toEqual([caseToBox]);
    expect(packagingSelectionError(line)).toBeNull();
    expect(canonicalSalesOrderItemPayload(line)).toMatchObject({
      unit: 'case',
      packagingSpecId: caseToBox.id,
    });
  });

  it('blocks a stale package spec before a base-unit request can be sent', () => {
    const line = {
      unit: '盒',
      packagingSpecId: caseToBox.id,
      packagingSpecs: [caseToBox],
    };
    expect(packagingSelectionError(line)).toContain('下单单位不一致');
  });

  it.each([
    ['片', 'slice'],
    ['slice', 'slice'],
    ['箱', 'case'],
    ['case', 'case'],
    ['g', 'g'],
    ['kg', 'kg'],
  ])('canonicalizes %s to %s without inventing a packaging spec', (unit, expected) => {
    const payload = canonicalSalesOrderItemPayload({ unit, packagingSpecs: [] });
    expect(payload.unit).toBe(expected);
    expect(payload).not.toHaveProperty('packagingSpecId');
  });
});

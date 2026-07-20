import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  calculateYieldRate,
  collectionDisplay,
  displayAuditUnit,
  formatAuditMoney,
  formatCollectedMoney,
  quantityInKilograms,
} from '../m67YieldCostAudit';

describe('M67 成品出厂核算审计口径', () => {
  it('uses the pinned 800g/box contract for 5kg → 4.5kg → 5box', () => {
    const firstStepYield = calculateYieldRate({
      inputQuantity: 5,
      inputUnit: 'kg',
      outputQuantity: 4.5,
      outputUnit: 'kg',
    });
    const secondStepYield = calculateYieldRate({
      inputQuantity: 4.5,
      inputUnit: 'kg',
      outputQuantity: 5,
      outputUnit: 'box',
      outputGramsPerUnit: 800,
    });
    const overallYield = calculateYieldRate({
      inputQuantity: 5,
      inputUnit: 'kg',
      outputQuantity: 5,
      outputUnit: 'box',
      outputGramsPerUnit: 800,
    });

    expect(firstStepYield).toBeCloseTo(0.9, 8);
    expect(secondStepYield).toBeCloseTo(0.88888889, 8);
    expect(overallYield).toBeCloseTo(0.8, 8);
    expect(quantityInKilograms(5, 'box', 800)).toBe(4);
  });

  it('fails closed when a mixed-unit conversion lacks a verified historical factor', () => {
    expect(calculateYieldRate({
      inputQuantity: 4.5,
      inputUnit: 'kg',
      outputQuantity: 5,
      outputUnit: 'box',
    })).toBeNull();
    expect(quantityInKilograms(5, 'box')).toBeNull();
  });

  it('localizes canonical packaging units without changing API values', () => {
    expect(displayAuditUnit('box')).toBe('盒');
    expect(displayAuditUnit('case')).toBe('箱');
    expect(displayAuditUnit('slice')).toBe('片');
  });

  it('never renders an unknown or unconfirmed zero cost as a collected ¥0.00', () => {
    expect(formatAuditMoney(null)).toBe('未归集');
    expect(collectionDisplay(null, 'MISSING_PRICE')).toEqual({
      label: '未归集/缺少价格',
      complete: false,
      confirmedZero: false,
    });
    expect(collectionDisplay(0)).toMatchObject({ complete: false, confirmedZero: false });
    expect(formatCollectedMoney(0)).toBe('未归集');
    expect(formatCollectedMoney(0, 'CONFIRMED_ZERO')).toBe('¥0.00');
    expect(collectionDisplay(0, 'CONFIRMED_ZERO')).toEqual({
      label: '已确认 0',
      complete: true,
      confirmedZero: true,
    });
  });

  it('wires the audit ledger, business order lookup, localized units and copy action into the page', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/production-analytics/M67YieldCost.vue'), 'utf8');

    expect(source).toContain('核算审计账');
    expect(source).toContain('原料明细');
    expect(source).toContain('包材明细');
    expect(source).toContain('人工明细');
    expect(source).toContain('设备、其他与总账勾稽');
    expect(source).toContain('出成率公式');
    expect(source).toContain('copyAuditDetails');
    expect(source).toContain('业务订单号 SO… / 内部ID');
    expect(source).toContain("get<SalesOrderListPage>(`/${fid}/sales/orders`");
    expect(source).toContain('displayAuditUnit(g.outputUnit');
    expect(source).toContain('formatCollectedMoney(row.amount');
  });
});

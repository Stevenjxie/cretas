import { describe, expect, it } from 'vitest';
import {
  formatPlanActualQuantity,
  sourceOrderDisplay,
  sourceOrderTarget,
} from '../productionPlanListPresentation';

describe('production plan list presentation', () => {
  it('localizes actual quantity without changing canonical payload units', () => {
    expect(formatPlanActualQuantity({ actualQuantity: 5, plannedUnit: 'box' })).toBe('5 盒');
    expect(formatPlanActualQuantity({ actualQuantity: 2, plannedUnit: 'case' })).toBe('2 箱');
    expect(formatPlanActualQuantity({ actualQuantity: 4.5, plannedUnit: 'kg' })).toBe('4.5 kg');
    expect(formatPlanActualQuantity({ actualQuantity: 1, plannedUnit: null })).toBe('1（单位未配置）');
  });

  it('shows and links the business sales-order number instead of exposing a UUID', () => {
    const row = {
      customerOrderNumber: 'SO-20260720-0001',
      sourceOrderId: 'ecd7f20b-21c2-4ea3-9103-2034d5d6547f',
    };
    expect(sourceOrderDisplay(row)).toBe('SO-20260720-0001');
    expect(sourceOrderTarget(row)).toEqual({
      path: '/sales/orders/ecd7f20b-21c2-4ea3-9103-2034d5d6547f',
    });
    expect(sourceOrderDisplay({ sourceOrderId: row.sourceOrderId })).toBe('业务订单号未同步');
  });

});

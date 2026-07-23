import { describe, expect, it } from 'vitest';
import {
  plannedQuantityForPayload,
  plannedQuantityRequired,
} from '../productionPlanQuantity';

describe('production plan quantity contract', () => {
  it('allows inventory production to be created without a target output quantity', () => {
    expect(plannedQuantityRequired('SAFETY_STOCK')).toBe(false);
    expect(plannedQuantityForPayload('SAFETY_STOCK', undefined)).toBeUndefined();
    expect(plannedQuantityForPayload('SAFETY_STOCK', 0)).toBeUndefined();
  });

  it('keeps a positive inventory-production target when the planner provides one', () => {
    expect(plannedQuantityForPayload('SAFETY_STOCK', 10)).toBe(10);
  });

  it('does not weaken quantity requirements for other plan sources', () => {
    expect(plannedQuantityRequired('MANUAL')).toBe(true);
    expect(plannedQuantityRequired('CUSTOMER_ORDER')).toBe(true);
    expect(plannedQuantityRequired('AI_FORECAST')).toBe(true);
  });
});

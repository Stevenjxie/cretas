import { describe, expect, it } from 'vitest';
import {
  buildSalesApprovalAmountCondition,
  parseSalesApprovalAmountThreshold,
} from '../salesApprovalCondition';

describe('sales approval graph edge condition', () => {
  it('round-trips the amount threshold through WorkflowEngine SpEL', () => {
    const condition = buildSalesApprovalAmountCondition(5000);
    expect(condition).toBe('#amount > 5000');
    expect(parseSalesApprovalAmountThreshold(condition)).toBe(5000);
  });

  it('accepts the persisted expression with or without the SpEL variable prefix', () => {
    expect(parseSalesApprovalAmountThreshold('amount > 1200.5')).toBe(1200.5);
    expect(parseSalesApprovalAmountThreshold(' #amount > 0 ')).toBe(0);
    expect(parseSalesApprovalAmountThreshold('#context.amount > 5000')).toBe(5000);
  });

  it('does not overwrite custom or invalid graph conditions', () => {
    expect(parseSalesApprovalAmountThreshold('#externalChannel == true')).toBeNull();
    expect(parseSalesApprovalAmountThreshold('#amount >= 5000')).toBeNull();
    expect(() => buildSalesApprovalAmountCondition(-1)).toThrow('金额阈值');
  });
});

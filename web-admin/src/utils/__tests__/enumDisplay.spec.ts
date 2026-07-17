import { describe, expect, it } from 'vitest';
import { enumLabel } from '../enumDisplay';

describe('enumLabel', () => {
  it('translates shared status and payment codes', () => {
    expect(enumLabel('FINANCE_APPROVED')).toBe('财务已审核');
    expect(enumLabel('BANK_TRANSFER')).toBe('银行转账');
  });

  it('never exposes an unknown enum as bare English', () => {
    expect(enumLabel('NEW_REMOTE_CODE')).toBe('未知状态（NEW_REMOTE_CODE）');
  });
});


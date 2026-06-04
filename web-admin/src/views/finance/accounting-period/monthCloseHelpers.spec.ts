import { describe, it, expect } from 'vitest';
import {
  computeAdjustWindow,
  canCloseFromChecks,
  reconciliationTagType,
} from './monthCloseHelpers';

describe('computeAdjustWindow', () => {
  const now = new Date('2026-06-04T10:00:00');

  it('未结账 (OPEN) → NOT_CLOSED', () => {
    const r = computeAdjustWindow('OPEN', undefined, now);
    expect(r.state).toBe('NOT_CLOSED');
    expect(r.label).toBe('—');
  });

  it('PENDING_CLOSE → NOT_CLOSED', () => {
    const r = computeAdjustWindow('PENDING_CLOSE', undefined, now);
    expect(r.state).toBe('NOT_CLOSED');
  });

  it('CLOSED 且 deadline 在未来 → OPEN_WINDOW + 剩余天数', () => {
    // deadline = now + 17 天
    const deadline = new Date('2026-06-21T10:00:00').toISOString();
    const r = computeAdjustWindow('CLOSED', deadline, now);
    expect(r.state).toBe('OPEN_WINDOW');
    expect(r.remainingDays).toBe(17);
    expect(r.label).toContain('17');
  });

  it('CLOSED 且 deadline 已过 → LOCKED', () => {
    const deadline = new Date('2026-05-30T10:00:00').toISOString();
    const r = computeAdjustWindow('CLOSED', deadline, now);
    expect(r.state).toBe('LOCKED');
    expect(r.remainingDays).toBe(0);
    expect(r.label).toContain('已锁定');
  });

  it('CLOSED 但无 deadline (旧行) → LOCKED (backwards compat)', () => {
    const r = computeAdjustWindow('CLOSED', undefined, now);
    expect(r.state).toBe('LOCKED');
    expect(r.label).toBe('已锁定');
  });

  it('不足 1 天也显示剩 1 天 (ceil)', () => {
    const deadline = new Date('2026-06-04T22:00:00').toISOString(); // +12h
    const r = computeAdjustWindow('CLOSED', deadline, now);
    expect(r.state).toBe('OPEN_WINDOW');
    expect(r.remainingDays).toBe(1);
  });
});

describe('canCloseFromChecks', () => {
  it('无 BLOCKING 失败 → true', () => {
    expect(
      canCloseFromChecks([
        { severity: 'BLOCKING', passed: true },
        { severity: 'WARNING', passed: false },
        { severity: 'INFO', passed: true },
      ])
    ).toBe(true);
  });

  it('BLOCKING 失败 → false', () => {
    expect(
      canCloseFromChecks([
        { severity: 'BLOCKING', passed: false },
        { severity: 'WARNING', passed: true },
      ])
    ).toBe(false);
  });

  it('WARNING 失败不阻塞 → true', () => {
    expect(
      canCloseFromChecks([{ severity: 'WARNING', passed: false }])
    ).toBe(true);
  });
});

describe('reconciliationTagType', () => {
  it('PASS → success', () => {
    expect(reconciliationTagType('PASS')).toBe('success');
  });
  it('WARNING → warning', () => {
    expect(reconciliationTagType('WARNING')).toBe('warning');
  });
  it('其他/undefined → info', () => {
    expect(reconciliationTagType(undefined)).toBe('info');
    expect(reconciliationTagType('X')).toBe('info');
  });
});

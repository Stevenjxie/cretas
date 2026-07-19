import { describe, expect, it, vi } from 'vitest';
import { InMemoryIdempotencyAttempts, stableBusinessSignature } from '../idempotency';

const ID1 = '00000000-0000-4000-8000-000000000001';
const ID2 = '00000000-0000-4000-8000-000000000002';
const ID3 = '00000000-0000-4000-8000-000000000003';

describe('AgentOps in-memory idempotency attempts', () => {
  it('reuses a request ID for an unchanged failed attempt and rotates it after change or success', () => {
    const ids = [ID1, ID2, ID3];
    const factory = vi.fn(() => ids.shift()!);
    const attempts = new InMemoryIdempotencyAttempts(factory);

    const firstSignature = stableBusinessSignature({ name: 'baseline', cases: [{ caseId: 'c1' }] });
    const sameSignature = stableBusinessSignature({ cases: [{ caseId: 'c1' }], name: 'baseline' });
    const changedSignature = stableBusinessSignature({ name: 'baseline-v2', cases: [{ caseId: 'c1' }] });

    expect(attempts.requestId('create', firstSignature)).toBe(ID1);
    expect(attempts.requestId('create', sameSignature)).toBe(ID1);
    expect(attempts.requestId('create', changedSignature)).toBe(ID2);
    attempts.complete('create', ID2);
    expect(attempts.requestId('create', changedSignature)).toBe(ID3);
    expect(factory).toHaveBeenCalledTimes(3);
  });

  it('keeps rerun attempts isolated by source experiment ID', () => {
    const ids = [ID1, ID2];
    const attempts = new InMemoryIdempotencyAttempts(() => ids.shift()!);
    const signature = stableBusinessSignature({ schemaVersion: '1.0' });

    expect(attempts.requestId('rerun:source-1', signature)).toBe(ID1);
    expect(attempts.requestId('rerun:source-1', signature)).toBe(ID1);
    expect(attempts.requestId('rerun:source-2', signature)).toBe(ID2);
  });
});

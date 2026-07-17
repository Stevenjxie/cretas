import { describe, expect, it } from 'vitest';
import { needsPrimaryOutputKindUpdate } from '../processOutputKindCompatibility';

describe('process output kind compatibility', () => {
  it('blocks a primary finished SKU on a semi-finished process until the process is updated', () => {
    expect(needsPrimaryOutputKindUpdate('SEMI_FINISHED', 'FINISHED_GOOD', true)).toBe(true);
  });

  it('allows matching primary output and mixed secondary byproducts', () => {
    expect(needsPrimaryOutputKindUpdate('FINISHED_GOOD', 'FINISHED_GOOD', true)).toBe(false);
    expect(needsPrimaryOutputKindUpdate('SEMI_FINISHED', 'FINISHED_GOOD', false)).toBe(false);
  });

  it('does not invent a restriction for legacy processes without a configured kind', () => {
    expect(needsPrimaryOutputKindUpdate(null, 'FINISHED_GOOD', true)).toBe(false);
  });
});

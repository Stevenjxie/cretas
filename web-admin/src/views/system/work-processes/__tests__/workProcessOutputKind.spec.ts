import { describe, expect, it } from 'vitest';
import {
  normalizeOutputMaterialKind,
  usesSemiFinishedCode,
} from '../workProcessOutputKind';

describe('work process output kind', () => {
  it('defaults missing legacy values to SEMI_FINISHED', () => {
    expect(normalizeOutputMaterialKind(undefined)).toBe('SEMI_FINISHED');
  });

  it('only keeps semi code controls for semi-finished output', () => {
    expect(usesSemiFinishedCode('SEMI_FINISHED')).toBe(true);
    expect(usesSemiFinishedCode('FINISHED_GOOD')).toBe(false);
  });
});

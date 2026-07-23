import { describe, expect, it } from 'vitest';
import { initialProcessSheetViewMode } from '../processSheetViewMode';

describe('process sheet default presentation', () => {
  it('defaults to the table view when no preference has been saved', () => {
    expect(initialProcessSheetViewMode(null)).toBe('grid');
    expect(initialProcessSheetViewMode('unexpected')).toBe('grid');
  });

  it('respects an explicit card preference', () => {
    expect(initialProcessSheetViewMode('card')).toBe('card');
  });
});

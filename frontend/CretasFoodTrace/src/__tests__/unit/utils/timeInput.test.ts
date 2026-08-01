import { isValidTime, normalizeTimeInput } from '../../../utils/timeInput';

describe('fixed HH:mm input', () => {
  it('inserts and restores the ASCII colon from digits', () => {
    expect(normalizeTimeInput('1')).toBe('1');
    expect(normalizeTimeInput('123')).toBe('12:3');
    expect(normalizeTimeInput('12：34')).toBe('12:34');
    expect(normalizeTimeInput('12345')).toBe('12:34');
  });

  it('validates hour and minute ranges', () => {
    expect(isValidTime('08:30')).toBe(true);
    expect(isValidTime('23:59')).toBe(true);
    expect(isValidTime('24:00')).toBe(false);
    expect(isValidTime('12:60')).toBe(false);
  });
});

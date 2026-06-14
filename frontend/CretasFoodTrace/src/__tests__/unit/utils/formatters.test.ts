import {
  displayProductName,
  formatCompactNumber,
  formatCurrency,
  formatDate,
  formatDateSlash,
  formatDateTime,
  formatDateTimeFull,
  formatMonthDay,
  formatNumberWithCommas,
  formatPercent,
  formatShortDateTime,
  formatTime,
  formatTimeFull,
} from '../../../utils/formatters';

describe('formatters', () => {
  const sampleDate = new Date(2026, 1, 9, 14, 30, 5);

  it('formats numbers without Intl dependencies', () => {
    expect(formatNumberWithCommas(12345)).toBe('12,345');
    expect(formatNumberWithCommas(-12345.67)).toBe('-12,345.67');
    expect(formatNumberWithCommas(null)).toBe('0');
    expect(formatCompactNumber(1234)).toBe('1,234');
    expect(formatCurrency(1234)).toContain('1,234');
    expect(formatPercent(0.856)).toBe('85.6%');
    expect(formatPercent(85.6)).toBe('85.6%');
    expect(formatPercent(null)).toBe('0%');
  });

  it('filters empty product names to a caller-provided fallback', () => {
    expect(displayProductName('  Product A  ', 'Pending')).toBe('Product A');
    expect(displayProductName('', 'Pending')).toBe('Pending');
    expect(displayProductName(null, 'Pending')).toBe('Pending');
  });

  it('formats dates and times manually', () => {
    expect(formatDate(sampleDate)).toBe('2026-02-09');
    expect(formatDateSlash(sampleDate)).toBe('2026/02/09');
    expect(formatMonthDay(sampleDate)).toBe('02-09');
    expect(formatTime(sampleDate)).toBe('14:30');
    expect(formatTimeFull(sampleDate)).toBe('14:30:05');
    expect(formatDateTime(sampleDate)).toBe('2026-02-09 14:30');
    expect(formatDateTimeFull(sampleDate)).toBe('2026-02-09 14:30:05');
    expect(formatShortDateTime(sampleDate)).toBe('02-09 14:30');
  });

  it('returns empty strings for invalid dates', () => {
    expect(formatDate('not-a-date')).toBe('');
    expect(formatTime(undefined)).toBe('');
  });
});

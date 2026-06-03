import { describe, it, expect } from 'vitest';
import { resolveAllHistoryRange, wideFallbackRange, isoDate } from '../analysisDefaults';

const NOW = new Date('2026-06-02T12:00:00Z');

describe('analysisDefaults — 全部历史默认窗 (WS4 Task 1 / #10/#11/#12)', () => {
  it('isoDate → YYYY-MM-DD', () => {
    expect(isoDate(new Date('2026-06-02T23:59:00Z'))).toBe('2026-06-02');
  });

  it('真实数据窗 → 原样返回 [minDate, maxDate] (全部历史)', () => {
    const r = resolveAllHistoryRange({ minDate: '2025-01-01', maxDate: '2025-12-31', dayCount: 365 }, 730, NOW);
    expect(r).toEqual(['2025-01-01', '2025-12-31']);
  });

  it('probe null → 宽回退窗 (NOT 近30天)', () => {
    const r = resolveAllHistoryRange(null, 730, NOW);
    // end = today, start = 730d ago → 远早于"近30天"
    expect(r[1]).toBe('2026-06-02');
    expect(r[0]).toBe(wideFallbackRange(730, NOW)[0]);
    // 关键回归断言: 起点不是"近30天" (#11)
    const thirtyDaysAgo = new Date(NOW); thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
    expect(r[0] < isoDate(thirtyDaysAgo)).toBe(true);
  });

  it('probe 有 null 边界 → 宽回退窗', () => {
    const r = resolveAllHistoryRange({ minDate: null, maxDate: null }, 730, NOW);
    expect(r).toEqual(wideFallbackRange(730, NOW));
  });

  it('wideFallbackRange(730) 起点约 2 年前', () => {
    const [start, end] = wideFallbackRange(730, NOW);
    expect(end).toBe('2026-06-02');
    expect(start).toBe('2024-06-03'); // 729天前 (含端点 → 730天窗)
  });
});

/**
 * WS2 #7 — buildRevenueInsight pure-fn tests (2026-06-02).
 *
 * Rules-first (0 LLM) insight from gold finance-summary + channel-breakdown.
 * Asserts: top store + 堂食 pct in the sentence; empty/insufficient data → null.
 */
import { describe, it, expect } from 'vitest';
import { buildRevenueInsight } from '../revenueInsight';

const stores = (rows: Array<[string, number]>) =>
  rows.map(([storeName, revenue], i) => ({ storeId: i + 1, storeName, revenue }));

const chans = (rows: Array<[string, number]>) =>
  rows.map(([channelName, sharePct], i) => ({
    channelId: i + 1,
    channelName,
    amount: 0,
    sharePct,
  }));

describe('buildRevenueInsight', () => {
  it('returns a sentence with the top store + 堂食 pct', () => {
    const s = buildRevenueInsight(
      stores([
        ['青花椒大融城店', 1_200_000],
        ['青花椒万象城店', 400_000],
      ]),
      chans([
        ['堂食', 68.5],
        ['外卖', 31.5],
      ]),
    );
    expect(s).toBeTruthy();
    expect(s).toContain('青花椒大融城店'); // top store
    expect(s).toContain('3.0 倍'); // 1.2M / 0.4M = 3.0
    expect(s).toContain('末位 青花椒万象城店');
    expect(s).toContain('堂食占 68.5%');
    expect(s!.endsWith('。')).toBe(true);
  });

  it('empty data → null (no fake insight)', () => {
    expect(buildRevenueInsight([], [])).toBeNull();
    expect(buildRevenueInsight(null, null)).toBeNull();
    expect(buildRevenueInsight(undefined, undefined)).toBeNull();
  });

  it('single store → reports its revenue, no 差距/倍 wording', () => {
    const s = buildRevenueInsight(stores([['独苗店', 500_000]]), []);
    expect(s).toBeTruthy();
    expect(s).toContain('独苗店');
    expect(s).not.toContain('倍');
    expect(s).not.toContain('末位');
  });

  it('near-equal stores → 均衡 wording instead of "1.0 倍"', () => {
    const s = buildRevenueInsight(
      stores([
        ['A 店', 1_000_000],
        ['B 店', 980_000],
      ]),
      [],
    );
    expect(s).toBeTruthy();
    expect(s).toContain('各店较均衡');
    expect(s).not.toContain('倍');
  });

  it('stores present but no 堂食 channel → store part only, no 堂食 clause', () => {
    const s = buildRevenueInsight(
      stores([
        ['大店', 900_000],
        ['小店', 300_000],
      ]),
      chans([['美团外卖', 60], ['饿了么', 40]]),
    );
    expect(s).toBeTruthy();
    expect(s).toContain('大店');
    expect(s).not.toContain('堂食占');
  });

  it('zero-revenue stores are filtered (≥2 positive needed for 差距)', () => {
    // only one positive-revenue store → single-store branch, no 倍
    const s = buildRevenueInsight(
      stores([
        ['有营收店', 700_000],
        ['零营收店', 0],
      ]),
      chans([['堂食', 55]]),
    );
    expect(s).toBeTruthy();
    expect(s).toContain('有营收店');
    expect(s).not.toContain('倍');
    expect(s).toContain('堂食占 55.0%');
  });
});

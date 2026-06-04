/**
 * #56 价值可视化回馈回路 — valueFeedbackFormat 纯函数测试 (2026-06-04)。
 *
 * 断言: 期间口径拆分 / null → '暂无数据' (禁填 0) / 预估橙·实测绿·暂无灰。
 */
import { describe, it, expect } from 'vitest';
import {
  formatValueAmount,
  buildValueChips,
  totalForPeriod,
  totalKind,
  KIND_COLORS,
} from '../valueFeedbackFormat';
import type { ValueSummary } from '@/api/smartbi/restaurantValueApi';

const SUMMARY: ValueSummary = {
  periodMonth: '2026-02',
  storeId: null,
  month: { total: 50849, shrinkageVariance: 12500, foodCostSavings: 20000, discountSavings: null },
  annual: { total: 472688, laborRigidity: 220188 },
  diagnosisCount: 3,
  criticalCount: 1,
  rxActionCount: 2,
  signalSources: [],
  confidenceNote: '预估口径',
  computedAt: null,
};

describe('formatValueAmount', () => {
  it('formats a number with thousands separators', () => {
    expect(formatValueAmount(50849)).toBe('¥50,849');
  });
  it('returns 暂无数据 for null (NOT ¥0)', () => {
    expect(formatValueAmount(null)).toBe('暂无数据');
    expect(formatValueAmount(undefined)).toBe('暂无数据');
    expect(formatValueAmount(0)).toBe('¥0'); // explicit 0 is a real value, not null
  });
});

describe('buildValueChips', () => {
  it('month period yields food/discount/shrinkage chips', () => {
    const chips = buildValueChips(SUMMARY, 'month');
    const labels = chips.map((c) => c.label);
    expect(labels).toContain('食材成本改善空间');
    expect(labels).toContain('折扣率改善空间');
    expect(labels).toContain('档口损溢超标');
  });

  it('shrinkage chip is measured (green), food chip is estimate (orange)', () => {
    const chips = buildValueChips(SUMMARY, 'month');
    const food = chips.find((c) => c.label === '食材成本改善空间')!;
    const shrink = chips.find((c) => c.label === '档口损溢超标')!;
    expect(food.kind).toBe('estimate');
    expect(KIND_COLORS[food.kind]).toBe('#FF9800');
    expect(shrink.kind).toBe('measured');
    expect(KIND_COLORS[shrink.kind]).toBe('#4CAF50');
  });

  it('null amount chip is kind=none (grey)', () => {
    const chips = buildValueChips(SUMMARY, 'month');
    const discount = chips.find((c) => c.label === '折扣率改善空间')!;
    expect(discount.amount).toBeNull();
    expect(discount.kind).toBe('none');
    expect(KIND_COLORS[discount.kind]).toBe('#9E9E9E');
  });

  it('annual period yields labor rigidity chip', () => {
    const chips = buildValueChips(SUMMARY, 'annual');
    expect(chips).toHaveLength(1);
    expect(chips[0].label).toBe('人工刚性节省');
    expect(chips[0].amount).toBe(220188);
    expect(chips[0].suffix).toBe('/年');
  });
});

describe('totalForPeriod / totalKind', () => {
  it('returns month vs annual totals', () => {
    expect(totalForPeriod(SUMMARY, 'month')).toBe(50849);
    expect(totalForPeriod(SUMMARY, 'annual')).toBe(472688);
  });

  it('total kind is none when total is null', () => {
    const empty: ValueSummary = {
      ...SUMMARY,
      month: { total: null, shrinkageVariance: null, foodCostSavings: null, discountSavings: null },
    };
    expect(totalKind(empty, 'month')).toBe('none');
  });

  it('total kind is estimate when total present', () => {
    expect(totalKind(SUMMARY, 'month')).toBe('estimate');
  });
});

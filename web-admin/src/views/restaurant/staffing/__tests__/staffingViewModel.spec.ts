import { describe, expect, it } from 'vitest';
import {
  detectStaffingHorizon,
  filterAndSortStaffingRows,
  gapLabel,
  isGroundedStaffingIntent,
  paginateStaffingRows,
  resolveStaffingAiQuery,
  STAFFING_QUICK_QUESTIONS,
  staffingQuestionForHorizon,
  staffingPerspective,
} from '../staffingViewModel';
import type { StaffingSummaryRow } from '@/types/restaurant-staffing';

function row(
  storeId: number,
  storeName: string,
  daypart: string,
  gap: number,
  predictedGuests: number,
  confidencePct: number,
): StaffingSummaryRow {
  return {
    storeId,
    storeName,
    daypart,
    predictedGuests,
    reservedGuests: 10,
    reservationCoveragePct: 50,
    recommendedStaff: 10,
    currentStaff: 10 - gap,
    gap,
    positiveGap: Math.max(0, gap),
    partTimePeople: Math.max(0, gap),
    confidencePct,
    averageHistoricalGuests: 100,
    historicalProductivity: null,
  };
}

describe('预测排班角色视图', () => {
  it('老板看连锁资源，店长看班次执行，人事看技能工时', () => {
    expect(staffingPerspective('restaurant_owner').focus).toBe('连锁资源');
    expect(staffingPerspective('restaurant_manager').focus).toBe('班次执行');
    expect(staffingPerspective('hr_admin').focus).toBe('技能与工时');
  });

  it('只允许排班写角色看到调整动作', () => {
    expect(staffingPerspective('restaurant_manager').canAdjust).toBe(true);
    expect(staffingPerspective('hr_admin').canAdjust).toBe(true);
    expect(staffingPerspective('finance_manager').canAdjust).toBe(false);
  });

  it('缺口文案不把负数说成缺人', () => {
    expect(gapLabel(2)).toBe('缺 2');
    expect(gapLabel(0)).toBe('已匹配');
    expect(gapLabel(-2)).toBe('余 2');
  });

  it('内置问题覆盖三个真实预测范围', () => {
    expect(STAFFING_QUICK_QUESTIONS).toEqual([
      '明天怎么排班',
      '下周需要多少兼职',
      '下个月各店人效安排',
    ]);
  });

  it('识别明确预测范围，并让快捷问题与页面范围一一对应', () => {
    expect(detectStaffingHorizon('明日午市怎么排')).toBe('tomorrow');
    expect(detectStaffingHorizon('未来7天需要多少兼职')).toBe('week');
    expect(detectStaffingHorizon('下月各店怎么安排')).toBe('month');
    expect(detectStaffingHorizon('晚市怎么安排')).toBeNull();
    expect(staffingQuestionForHorizon('week')).toBe('下周需要多少兼职');
  });

  it('新问题缺少时间时绑定当前 FactBook，连续追问则保留原话交给会话解析', () => {
    expect(resolveStaffingAiQuery('晚市怎么安排', 'tomorrow', false)).toEqual({
      displayQuestion: '晚市怎么安排',
      requestQuestion: '明天，晚市怎么安排',
      horizon: 'tomorrow',
      explicitHorizon: false,
    });
    expect(resolveStaffingAiQuery('那晚市呢', 'week', true)).toEqual({
      displayQuestion: '那晚市呢',
      requestQuestion: '那晚市呢',
      horizon: 'week',
      explicitHorizon: false,
    });
    expect(resolveStaffingAiQuery('下个月各店怎么安排', 'tomorrow', false)).toMatchObject({
      requestQuestion: '下个月各店怎么安排',
      horizon: 'month',
      explicitHorizon: true,
    });
  });

  it('只有真实排班意图可以标记为同一预测 FactBook 回答', () => {
    expect(isGroundedStaffingIntent('RESTAURANT_OPS_STAFFING_ADVICE')).toBe(true);
    expect(isGroundedStaffingIntent('RESTAURANT_DAILY_REVENUE')).toBe(false);
    expect(isGroundedStaffingIntent(null)).toBe(false);
  });

  it('按门店、时段和缺口组合筛选，并默认优先展示最大缺口', () => {
    const rows = [
      row(1, '西湖店', '午市', 2, 180, 80),
      row(1, '西湖店', '晚市', 5, 260, 72),
      row(2, '湖滨店', '晚市', -1, 120, 60),
      row(3, '城西店', '晚市', 0, 90, 88),
    ];
    expect(filterAndSortStaffingRows(rows, {
      storeId: 1,
      daypart: '晚市',
      gap: 'shortage',
      sort: 'gap-desc',
    }).map((item) => item.gap)).toEqual([5]);
    expect(filterAndSortStaffingRows(rows, {
      storeId: null,
      daypart: '',
      gap: 'shortage',
      sort: 'gap-desc',
    }).map((item) => item.gap)).toEqual([5, 2]);
    expect(rows.map((item) => item.storeName)).toEqual(['西湖店', '西湖店', '湖滨店', '城西店']);
  });

  it('支持需求、置信度和门店排序', () => {
    const rows = [
      row(2, '湖滨店', '午市', 0, 120, 82),
      row(1, '西湖店', '晚市', 1, 260, 61),
      row(3, '城西店', '午市', 2, 80, 76),
    ];
    const base = { storeId: null, daypart: '', gap: 'all' as const };
    expect(filterAndSortStaffingRows(rows, { ...base, sort: 'demand-desc' })[0].storeName).toBe('西湖店');
    expect(filterAndSortStaffingRows(rows, { ...base, sort: 'confidence-asc' })[0].confidencePct).toBe(61);
    expect(filterAndSortStaffingRows(rows, { ...base, sort: 'store' }).map((item) => item.storeName))
      .toEqual(['城西店', '湖滨店', '西湖店']);
  });

  it('分页会限制渲染行数并在筛选后自动收敛越界页码', () => {
    const rows = Array.from({ length: 23 }, (_, index) => index + 1);
    expect(paginateStaffingRows(rows, 2, 10)).toEqual({
      page: 2,
      pageSize: 10,
      total: 23,
      totalPages: 3,
      from: 11,
      to: 20,
      rows: rows.slice(10, 20),
    });
    const clamped = paginateStaffingRows(rows.slice(0, 3), 3, 10);
    expect(clamped.page).toBe(1);
    expect(clamped.rows).toEqual([1, 2, 3]);
    expect(paginateStaffingRows([], 8, 0)).toMatchObject({
      page: 1,
      pageSize: 10,
      total: 0,
      from: 0,
      to: 0,
      rows: [],
    });
  });
});

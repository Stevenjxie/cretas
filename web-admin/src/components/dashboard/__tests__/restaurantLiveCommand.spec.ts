import { describe, expect, it } from 'vitest';
import {
  buildRestaurantCommandMetrics,
  latestStaffingSourceUpdate,
  resolveRestaurantTransmissionState,
} from '../restaurantLiveCommand';
import type { StaffingDashboard } from '@/types/restaurant-staffing';

function staffing(overrides: Partial<StaffingDashboard> = {}): StaffingDashboard {
  return {
    factoryId: 'RES_3101_009',
    horizon: 'tomorrow',
    horizonLabel: '明天',
    windowStart: '2026-08-04',
    windowEnd: '2026-08-04',
    generatedAt: '2026-08-03T16:00:00+08:00',
    asOf: '2026-08-03',
    numericSource: 'forecast_factbook_only',
    historicalProductivityRule: 'evidence_only_not_gap_input',
    sources: [
      { source: 'platform-a', isSimulated: false, updatedAt: '2026-08-03T15:58:00+08:00' },
      { source: 'platform-b', isSimulated: true, updatedAt: '2026-08-03T15:59:00+08:00' },
    ],
    summary: {
      predictedGuests: 486,
      reservedGuests: 212,
      reservationOrders: 58,
      reservationCoveragePct: 43.6,
      recommendedStaff: 32,
      currentStaff: 27,
      positiveGap: 5,
      partTimePeople: 3,
      confidencePct: 78.4,
      storeCount: 2,
    },
    summaryRows: [],
    dailyRows: [],
    liveStream: {
      windowMinutes: 15,
      pollIntervalSeconds: 60,
      eventCount: 6,
      guestCount: 24,
      latestEventAt: '2026-08-03T15:59:00+08:00',
      minuteBuckets: [],
      recentEvents: [],
    },
    ...overrides,
  };
}

describe('餐饮实时经营指挥屏', () => {
  it('所有业务数字只从经营汇总和预测 FactBook 映射', () => {
    const metrics = buildRestaurantCommandMetrics({
      todayRequisitions: 7,
      pendingApprovalCount: 2,
      monthWastageCost: 999,
      latestStocktakingDate: null,
    }, staffing());

    expect(metrics.map((item) => [item.key, item.value])).toEqual([
      ['requisitions', '7'],
      ['pending', '2'],
      ['reservationOrders', '58'],
      ['liveGuests', '24'],
      ['predictedGuests', '486'],
      ['staffing', '32 / 27'],
      ['gap', '5'],
      ['confidence', '78.4'],
    ]);
    expect(metrics.slice(2).every((item) => item.source.includes('FactBook'))).toBe(true);
    expect(metrics.find((item) => item.key === 'gap')?.detail).not.toContain('历史人效');
  });

  it('没有响应时显示缺省符号，不用演示数填充', () => {
    expect(buildRestaurantCommandMetrics(null, null).every((item) => item.value === '—')).toBe(true);
  });

  it('连接、刷新、部分失败和成功状态由真实请求结果决定', () => {
    expect(resolveRestaurantTransmissionState({
      loading: true, hasOps: false, hasStaffing: false, opsExpected: true, staffingExpected: true, hasError: false,
    })).toBe('connecting');
    expect(resolveRestaurantTransmissionState({
      loading: true, hasOps: true, hasStaffing: true, opsExpected: true, staffingExpected: true, hasError: false,
    })).toBe('refreshing');
    expect(resolveRestaurantTransmissionState({
      loading: false, hasOps: true, hasStaffing: false, opsExpected: true, staffingExpected: true, hasError: true,
    })).toBe('partial');
    expect(resolveRestaurantTransmissionState({
      loading: false, hasOps: true, hasStaffing: true, opsExpected: true, staffingExpected: true, hasError: false,
    })).toBe('live');
    expect(resolveRestaurantTransmissionState({
      loading: false, hasOps: false, hasStaffing: true, opsExpected: false, staffingExpected: true, hasError: false,
    })).toBe('live');
  });

  it('来源更新时间取真实最新事件时间，缺少来源时回退 FactBook 生成时间', () => {
    expect(latestStaffingSourceUpdate(staffing())).toBe('2026-08-03T15:59:00+08:00');
    expect(latestStaffingSourceUpdate(staffing({ sources: [] }))).toBe('2026-08-03T16:00:00+08:00');
  });
});

import type { StaffingDashboard } from '@/types/restaurant-staffing';

export interface RestaurantOpsSnapshot {
  todayRequisitions: number;
  pendingApprovalCount: number;
  monthWastageCost: number;
  latestStocktakingDate: string | null;
}

export type CommandMetricTone = 'primary' | 'neutral' | 'warning' | 'danger';

export interface RestaurantCommandMetric {
  key: string;
  label: string;
  value: string;
  unit: string;
  detail: string;
  source: string;
  tone: CommandMetricTone;
}

export type RestaurantTransmissionState = 'idle' | 'connecting' | 'refreshing' | 'live' | 'partial' | 'error';

export function buildRestaurantCommandMetrics(
  ops: RestaurantOpsSnapshot | null,
  staffing: StaffingDashboard | null,
): RestaurantCommandMetric[] {
  const summary = staffing?.summary;
  return [
    {
      key: 'requisitions',
      label: '今日领料',
      value: ops ? ops.todayRequisitions.toLocaleString('zh-CN') : '—',
      unit: '单',
      detail: '业务汇总接口',
      source: 'Java restaurant-dashboard',
      tone: 'neutral',
    },
    {
      key: 'pending',
      label: '待审批',
      value: ops ? ops.pendingApprovalCount.toLocaleString('zh-CN') : '—',
      unit: '单',
      detail: ops?.pendingApprovalCount ? '有事项等待处理' : '当前接口返回值',
      source: 'Java restaurant-dashboard',
      tone: ops?.pendingApprovalCount ? 'warning' : 'neutral',
    },
    {
      key: 'reservationOrders',
      label: '当前预订订单',
      value: summary?.reservationOrders !== undefined
        ? summary.reservationOrders.toLocaleString('zh-CN')
        : '—',
      unit: '单',
      detail: staffing ? `${staffing.windowStart} · 明日预测输入` : '等待预订 FactBook',
      source: 'Python reservation FactBook',
      tone: 'primary',
    },
    {
      key: 'liveGuests',
      label: '近 15 分钟新增',
      value: staffing?.liveStream
        ? staffing.liveStream.guestCount.toLocaleString('zh-CN')
        : '—',
      unit: '人',
      detail: staffing?.liveStream
        ? `${staffing.liveStream.eventCount.toLocaleString('zh-CN')} 笔实时事件`
        : '等待实时事件流',
      source: 'Python reservation FactBook',
      tone: 'primary',
    },
    {
      key: 'predictedGuests',
      label: '明日预测客流',
      value: summary ? summary.predictedGuests.toLocaleString('zh-CN') : '—',
      unit: '人',
      detail: staffing ? `${staffing.windowStart} · 全部门店` : '等待预测 FactBook',
      source: 'Python forecast FactBook',
      tone: 'primary',
    },
    {
      key: 'staffing',
      label: '建议 / 现有人数',
      value: summary ? `${summary.recommendedStaff} / ${summary.currentStaff}` : '—',
      unit: '人',
      detail: '门店×时段峰值班次汇总',
      source: 'Python forecast FactBook',
      tone: 'neutral',
    },
    {
      key: 'gap',
      label: '正向人力缺口',
      value: summary ? summary.positiveGap.toLocaleString('zh-CN') : '—',
      unit: '人',
      detail: '预测需求与岗位约束计算',
      source: 'Python forecast FactBook',
      tone: summary?.positiveGap ? 'danger' : 'neutral',
    },
    {
      key: 'confidence',
      label: '预测置信度',
      value: summary ? summary.confidencePct.toFixed(1) : '—',
      unit: '%',
      detail: '预订覆盖与历史窗口完整度',
      source: 'Python forecast FactBook',
      tone: 'primary',
    },
  ];
}

export function resolveRestaurantTransmissionState(input: {
  loading: boolean;
  hasOps: boolean;
  hasStaffing: boolean;
  opsExpected: boolean;
  staffingExpected: boolean;
  hasError: boolean;
}): RestaurantTransmissionState {
  const hasAnyData = input.hasOps || input.hasStaffing;
  if (input.loading) return hasAnyData ? 'refreshing' : 'connecting';
  if (input.hasError) return hasAnyData ? 'partial' : 'error';
  if (
    (input.opsExpected || input.staffingExpected)
    && (!input.opsExpected || input.hasOps)
    && (!input.staffingExpected || input.hasStaffing)
  ) return 'live';
  return 'idle';
}

export function latestStaffingSourceUpdate(dashboard: StaffingDashboard | null): string | null {
  if (!dashboard) return null;
  const timestamps = dashboard.sources
    .map((source) => source.updatedAt)
    .filter((value): value is string => Boolean(value))
    .map((value) => ({ value, time: new Date(value).getTime() }))
    .filter((item) => Number.isFinite(item.time))
    .sort((left, right) => right.time - left.time);
  return timestamps[0]?.value ?? dashboard.generatedAt ?? null;
}

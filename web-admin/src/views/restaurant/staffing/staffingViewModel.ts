import type { StaffingHorizon, StaffingSummaryRow } from '@/types/restaurant-staffing';

export interface StaffingPerspective {
  label: string;
  title: string;
  description: string;
  focus: string;
  canAdjust: boolean;
}

export type StaffingGapFilter = 'all' | 'shortage' | 'balanced' | 'surplus';
export type StaffingSortMode = 'gap-desc' | 'demand-desc' | 'confidence-asc' | 'store';

export interface StaffingRowFilter {
  storeId: number | null;
  daypart: string;
  gap: StaffingGapFilter;
  sort: StaffingSortMode;
}

export interface StaffingPage<T> {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
  from: number;
  to: number;
  rows: T[];
}

const WRITE_ROLES = new Set([
  'restaurant_owner',
  'restaurant_manager',
  'hr_admin',
  'factory_super_admin',
  'platform_admin',
  'permission_admin',
]);

export const STAFFING_QUICK_QUESTIONS = [
  '明天怎么排班',
  '下周需要多少兼职',
  '下个月各店人效安排',
] as const;

export const STAFFING_INTENT_CODE = 'RESTAURANT_OPS_STAFFING_ADVICE';

const STAFFING_HORIZON_QUESTIONS: Record<StaffingHorizon, string> = {
  tomorrow: STAFFING_QUICK_QUESTIONS[0],
  week: STAFFING_QUICK_QUESTIONS[1],
  month: STAFFING_QUICK_QUESTIONS[2],
};

const STAFFING_HORIZON_LABELS: Record<StaffingHorizon, string> = {
  tomorrow: '明天',
  week: '下周',
  month: '下个月',
};

export interface StaffingAiQueryResolution {
  displayQuestion: string;
  requestQuestion: string;
  horizon: StaffingHorizon;
  explicitHorizon: boolean;
}

export function detectStaffingHorizon(question: string): StaffingHorizon | null {
  const text = question.trim();
  if (/明天|明日|翌日/.test(text)) return 'tomorrow';
  if (/下周|下一周|未来(?:一|1)周|未来7天/.test(text)) return 'week';
  if (/下个月|下月|未来(?:一个|1个)月|未来30天/.test(text)) return 'month';
  return null;
}

export function staffingQuestionForHorizon(horizon: StaffingHorizon): string {
  return STAFFING_HORIZON_QUESTIONS[horizon];
}

export function resolveStaffingAiQuery(
  question: string,
  currentHorizon: StaffingHorizon,
  continuingSession: boolean,
): StaffingAiQueryResolution {
  const displayQuestion = question.trim();
  const detectedHorizon = detectStaffingHorizon(displayQuestion);
  const resolvedHorizon = detectedHorizon ?? currentHorizon;
  return {
    displayQuestion,
    requestQuestion: continuingSession || detectedHorizon
      ? displayQuestion
      : `${STAFFING_HORIZON_LABELS[currentHorizon]}，${displayQuestion}`,
    horizon: resolvedHorizon,
    explicitHorizon: detectedHorizon !== null,
  };
}

export function isGroundedStaffingIntent(intentCode: string | null | undefined): boolean {
  return intentCode === STAFFING_INTENT_CODE;
}

export function staffingPerspective(role: string): StaffingPerspective {
  if (role === 'hr_admin') {
    return {
      label: '人事视图',
      title: '未来人力与技能覆盖',
      description: '先看兼职需求、技能缺口和周工时，再确认门店班次。',
      focus: '技能与工时',
      canAdjust: true,
    };
  }
  if (role === 'restaurant_manager') {
    return {
      label: '店长视图',
      title: '门店时段排班',
      description: '按日期和时段核对预订、预测客流与现场人手。',
      focus: '班次执行',
      canAdjust: true,
    };
  }
  return {
    label: role === 'restaurant_owner' ? '老板视图' : '管理视图',
    title: '连锁需求与人效安排',
    description: '横向比较各店预订覆盖、需求峰值和正向人力缺口。',
    focus: '连锁资源',
    canAdjust: WRITE_ROLES.has(role),
  };
}

export function gapLabel(gap: number): string {
  if (gap > 0) return `缺 ${gap}`;
  if (gap < 0) return `余 ${Math.abs(gap)}`;
  return '已匹配';
}

export function gapTagType(gap: number): 'danger' | 'success' | 'info' {
  if (gap > 0) return 'danger';
  if (gap < 0) return 'info';
  return 'success';
}

function compareStore(left: StaffingSummaryRow, right: StaffingSummaryRow): number {
  return left.storeName.localeCompare(right.storeName, 'zh-CN')
    || left.daypart.localeCompare(right.daypart, 'zh-CN');
}

export function filterAndSortStaffingRows(
  rows: readonly StaffingSummaryRow[],
  filter: StaffingRowFilter,
): StaffingSummaryRow[] {
  const filtered = rows.filter((row) => {
    if (filter.storeId !== null && row.storeId !== filter.storeId) return false;
    if (filter.daypart && row.daypart !== filter.daypart) return false;
    if (filter.gap === 'shortage' && row.gap <= 0) return false;
    if (filter.gap === 'balanced' && row.gap !== 0) return false;
    if (filter.gap === 'surplus' && row.gap >= 0) return false;
    return true;
  });

  return filtered.sort((left, right) => {
    if (filter.sort === 'demand-desc') {
      return right.predictedGuests - left.predictedGuests || compareStore(left, right);
    }
    if (filter.sort === 'confidence-asc') {
      return left.confidencePct - right.confidencePct || compareStore(left, right);
    }
    if (filter.sort === 'store') return compareStore(left, right);
    return right.gap - left.gap
      || right.predictedGuests - left.predictedGuests
      || compareStore(left, right);
  });
}

export function paginateStaffingRows<T>(
  rows: readonly T[],
  requestedPage: number,
  requestedPageSize: number,
): StaffingPage<T> {
  const pageSize = Number.isFinite(requestedPageSize) && requestedPageSize > 0
    ? Math.floor(requestedPageSize)
    : 10;
  const total = rows.length;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const page = Math.min(Math.max(1, Math.floor(requestedPage) || 1), totalPages);
  const start = (page - 1) * pageSize;
  const pageRows = rows.slice(start, start + pageSize);
  return {
    page,
    pageSize,
    total,
    totalPages,
    from: total === 0 ? 0 : start + 1,
    to: total === 0 ? 0 : start + pageRows.length,
    rows: pageRows,
  };
}

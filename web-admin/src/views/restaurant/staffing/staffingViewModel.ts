export interface StaffingPerspective {
  label: string;
  title: string;
  description: string;
  focus: string;
  canAdjust: boolean;
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

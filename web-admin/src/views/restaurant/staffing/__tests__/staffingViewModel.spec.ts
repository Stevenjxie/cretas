import { describe, expect, it } from 'vitest';
import {
  gapLabel,
  STAFFING_QUICK_QUESTIONS,
  staffingPerspective,
} from '../staffingViewModel';

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
});

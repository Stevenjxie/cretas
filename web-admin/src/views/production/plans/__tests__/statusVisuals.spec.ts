import { describe, expect, it } from 'vitest';
import {
  getPlanStatusText,
  getPlanStatusType,
  planRowClassNameByStatus,
  planStatusClass,
  planStatusTone,
} from '../statusVisuals';

describe('production plan status visuals', () => {
  it.each([
    ['PENDING', 'pending', 'plan-row-pending', 'plan-status-pending', 'warning', '未完成'],
    ['PLANNED', 'pending', 'plan-row-pending', 'plan-status-pending', 'warning', '待执行'],
    ['IN_PROGRESS', 'in-progress', 'plan-row-in-progress', 'plan-status-in-progress', 'warning', '进行中'],
    ['COMPLETED', 'completed', 'plan-row-completed', 'plan-status-completed', 'success', '已完成'],
    ['EXCEPTION', 'exception', 'plan-row-exception', 'plan-status-exception', 'danger', '异常'],
  ])('maps %s to scannable row and tag visuals', (status, tone, rowClass, tagClass, type, label) => {
    expect(planStatusTone(status)).toBe(tone);
    expect(planRowClassNameByStatus(status)).toBe(rowClass);
    expect(planStatusClass(status)).toBe(tagClass);
    expect(getPlanStatusType(status)).toBe(type);
    expect(getPlanStatusText(status)).toBe(label);
  });

  it('keeps unknown statuses neutral without inventing a row color', () => {
    expect(planStatusTone('PREPARED')).toBe('default');
    expect(planRowClassNameByStatus('PREPARED')).toBe('');
    expect(planStatusClass('PREPARED')).toBe('plan-status-default');
    expect(getPlanStatusType('PREPARED')).toBe('info');
    expect(getPlanStatusText('PREPARED')).toBe('草稿');
  });
});

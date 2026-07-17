import { describe, expect, it } from 'vitest';
import { findLabelConflict, nextSegmentCode, type SegmentRuleNode } from './materialSegmentRules';

const rows: SegmentRuleNode[] = [
  { id: 1, level: 1, segmentCode: '001', segmentLabel: '原料', parentCode: null },
  { id: 2, level: 1, segmentCode: '002', segmentLabel: '辅料', parentCode: null },
  { id: 3, level: 2, segmentCode: '001001', segmentLabel: '禽肉类', parentCode: '001' },
  { id: 4, level: 3, segmentCode: '0010010001', segmentLabel: '黄油鸡', parentCode: '001001' },
  { id: 5, level: 3, segmentCode: '0010010003', segmentLabel: '三黄鸡', parentCode: '001001' },
];

describe('material segment rules', () => {
  it('allocates the next code inside the selected hierarchy without user input', () => {
    expect(nextSegmentCode(rows, 1, null)).toBe('003');
    expect(nextSegmentCode(rows, 2, '001')).toBe('001002');
    expect(nextSegmentCode(rows, 3, '001001')).toBe('0010010004');
    expect(nextSegmentCode(rows, 3, null)).toBe('');
  });

  it('detects reused labels across levels while allowing the edited row itself', () => {
    expect(findLabelConflict(rows, ' 黄油鸡 ', null)?.segmentCode).toBe('0010010001');
    expect(findLabelConflict(rows, '黄油鸡', 4)).toBeUndefined();
  });
});

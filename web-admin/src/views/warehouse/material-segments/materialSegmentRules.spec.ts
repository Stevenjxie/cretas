import { describe, expect, it } from 'vitest';
import { findLabelConflict, type SegmentRuleNode } from './materialSegmentRules';

const rows: SegmentRuleNode[] = [
  { id: 1, level: 1, segmentLabel: '原料', parentId: null },
  { id: 2, level: 1, segmentLabel: '辅料', parentId: null },
  { id: 3, level: 2, segmentLabel: '禽肉类', parentId: 1 },
  { id: 4, level: 3, segmentLabel: '黄油鸡', parentId: 3 },
  { id: 5, level: 3, segmentLabel: '三黄鸡', parentId: 3 },
];

describe('material classification rules', () => {
  it('detects reused labels across levels while allowing the edited row itself', () => {
    expect(findLabelConflict(rows, ' 黄油鸡 ', null)?.id).toBe(4);
    expect(findLabelConflict(rows, '黄油鸡', 4)).toBeUndefined();
  });
});

import { describe, it, expect } from 'vitest';
import {
  isCountUnit,
  hasValidGrams,
  boxAvailableKg,
  kgToBox,
  countUnitFeedWarning,
  countUnitLabelSuffix,
} from '../feedUnitConversion';

describe('feedUnitConversion — 盒⇄kg 折算 (逐道投料防呆)', () => {
  describe('isCountUnit', () => {
    it('识别计数单位 盒/个/件/只', () => {
      expect(isCountUnit('盒')).toBe(true);
      expect(isCountUnit('个')).toBe(true);
      expect(isCountUnit('件')).toBe(true);
      expect(isCountUnit('只')).toBe(true);
    });
    it('kg/重量单位/空 → 非计数', () => {
      expect(isCountUnit('kg')).toBe(false);
      expect(isCountUnit('千克')).toBe(false);
      expect(isCountUnit('')).toBe(false);
      expect(isCountUnit(null)).toBe(false);
      expect(isCountUnit(undefined)).toBe(false);
    });
  });

  describe('hasValidGrams', () => {
    it('>0 有效; null/0/负 无效', () => {
      expect(hasValidGrams(200)).toBe(true);
      expect(hasValidGrams(0.5)).toBe(true);
      expect(hasValidGrams(null)).toBe(false);
      expect(hasValidGrams(undefined)).toBe(false);
      expect(hasValidGrams(0)).toBe(false);
      expect(hasValidGrams(-1)).toBe(false);
    });
  });

  describe('boxAvailableKg — 可用盒数 × 每盒克重 / 1000', () => {
    it('200g/盒, 余 10 盒 → 2kg', () => {
      expect(boxAvailableKg(10, 200)).toBe(2);
    });
    it('500g/盒, 余 3 盒 → 1.5kg', () => {
      expect(boxAvailableKg(3, 500)).toBe(1.5);
    });
    it('缺每盒克重 → null (诚实, 禁止臆造)', () => {
      expect(boxAvailableKg(10, null)).toBeNull();
      expect(boxAvailableKg(10, 0)).toBeNull();
    });
  });

  describe('kgToBox — kg × 1000 / 每盒克重', () => {
    it('200g/盒, 投 2kg → 10 盒', () => {
      expect(kgToBox(2, 200)).toBe(10);
    });
    it('缺每盒克重 → null', () => {
      expect(kgToBox(2, null)).toBeNull();
    });
  });

  describe('countUnitFeedWarning — 盒装投料防呆 (超投/缺克重)', () => {
    it('(a) 缺每盒克重 → 拦截文案 (honest-null, 禁止臆造)', () => {
      const w = countUnitFeedWarning('盒', null, 10, 2, '该成品来源');
      expect(w).toContain('未配置每盒标准克重');
      expect(w).toContain('无法折算kg投料');
    });
    it('(b) 用量在可投 kg 内 → null (通过): 200g/盒, 余10盒=2kg, 投 1.5kg', () => {
      expect(countUnitFeedWarning('盒', 200, 10, 1.5, '该成品来源')).toBeNull();
    });
    it('(b) 用量正好等于可投 kg → null (边界通过): 余10盒=2kg, 投 2kg', () => {
      expect(countUnitFeedWarning('盒', 200, 10, 2, '该成品来源')).toBeNull();
    });
    it('(c) 用量超出可投 kg → 拦截"超出": 余10盒=2kg, 投 2.2kg', () => {
      const w = countUnitFeedWarning('盒', 200, 10, 2.2, '该成品来源');
      expect(w).toContain('超出可投 2.00kg');
      expect(w).toContain('余10盒 × 每盒200g');
    });
  });

  describe('countUnitLabelSuffix — 下拉标签折算后缀', () => {
    it('盒装 + 每盒克重 → " ≈ M kg (每盒 Xg)"', () => {
      expect(countUnitLabelSuffix('盒', 200, 10)).toBe(' ≈ 2.00kg (每盒200g)');
    });
    it('盒装缺每盒克重 → 警告后缀 (提示未配)', () => {
      expect(countUnitLabelSuffix('盒', null, 10)).toContain('未配每盒克重');
    });
    it('kg 源 → 无后缀 (空串)', () => {
      expect(countUnitLabelSuffix('kg', null, 10)).toBe('');
    });
  });
});

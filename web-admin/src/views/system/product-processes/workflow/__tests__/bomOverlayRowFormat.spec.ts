import { describe, expect, it } from 'vitest';
import {
  formatAuxiliaryDosageText,
  formatFriendlyNumber,
  formatPackagingDosageText,
  formatPackagingNaturalHint,
} from '../bomOverlayRowFormat';

describe('formatFriendlyNumber', () => {
  it('去掉多余小数位', () => {
    expect(formatFriendlyNumber(2)).toBe('2');
    expect(formatFriendlyNumber(0.05)).toBe('0.05');
  });

  it('非数字返回 em dash 占位', () => {
    expect(formatFriendlyNumber(Number.NaN)).toBe('—');
    expect(formatFriendlyNumber(undefined)).toBe('—');
  });
});

describe('formatAuxiliaryDosageText', () => {
  it('原样拼单位, 不再折算', () => {
    expect(formatAuxiliaryDosageText(12)).toBe('12 g/kg');
    expect(formatAuxiliaryDosageText(2.5)).toBe('2.5 g/kg');
  });

  it('缺失/非正数返回 undefined, 不能显示 0', () => {
    expect(formatAuxiliaryDosageText(null)).toBeUndefined();
    expect(formatAuxiliaryDosageText(undefined)).toBeUndefined();
    expect(formatAuxiliaryDosageText(0)).toBeUndefined();
    expect(formatAuxiliaryDosageText(-1)).toBeUndefined();
  });
});

describe('formatPackagingDosageText', () => {
  it('拼出「每 1 baseUnit 用量」', () => {
    expect(formatPackagingDosageText(0.05, '个', 'kg')).toBe('0.05 个/kg');
  });

  it('缺失/非正数/缺单位返回空串, 不能显示 0', () => {
    expect(formatPackagingDosageText(null, '个', 'kg')).toBe('');
    expect(formatPackagingDosageText(0, '个', 'kg')).toBe('');
    expect(formatPackagingDosageText(1, null, 'kg')).toBe('');
  });
});

describe('formatPackagingNaturalHint', () => {
  it('算出 dosageText 的倒数表达', () => {
    // 0.05 个/kg → 1 个能包 20 kg
    expect(formatPackagingNaturalHint(0.05, '个', 'kg')).toBe('= 1 个 / 20 kg');
  });

  it('倒数方向不能算反 —— 分子分母各自归位', () => {
    // 2 个/kg (每 kg 要 2 个) → 1 个只能包 0.5 kg, 不是 2 kg
    expect(formatPackagingNaturalHint(2, '个', 'kg')).toBe('= 1 个 / 0.5 kg');
  });

  it('缺失/非正数/缺单位返回 undefined, 不能设成空串', () => {
    expect(formatPackagingNaturalHint(null, '个', 'kg')).toBeUndefined();
    expect(formatPackagingNaturalHint(0, '个', 'kg')).toBeUndefined();
    expect(formatPackagingNaturalHint(0.05, null, 'kg')).toBeUndefined();
  });
});

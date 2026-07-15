import { describe, expect, it } from 'vitest';
import { composeProductSpecification } from '@/utils/productSpecification';

describe('composeProductSpecification', () => {
  it('使用中文克和产品基本单位生成规范规格', () => {
    expect(composeProductSpecification(200, '盒', [
      { packageUnit: '箱', baseUnit: '克', conversionFactor: 50 },
    ])).toBe('200g/盒 50盒/箱 10kg/箱');
  });

  it('基本单位变更时同步标准克重和每条装箱换算', () => {
    expect(composeProductSpecification(200, '件', [
      { packageUnit: '箱', baseUnit: '盒', conversionFactor: 12 },
      { packageUnit: '框', baseUnit: '盒', conversionFactor: 24 },
    ])).toBe('200g/件 12件/箱 2.4kg/箱 24件/框 4.8kg/框');
  });

  it('净重和装箱总重达到1000g时自动使用kg并去除无意义小数', () => {
    expect(composeProductSpecification(1200, '袋', [
      { packageUnit: '箱', baseUnit: '袋', conversionFactor: 10 },
    ])).toBe('1.2kg/袋 10袋/箱 12kg/箱');
  });

  it('忽略不完整或与基本单位相同的包装行', () => {
    expect(composeProductSpecification(null, '件', [
      { packageUnit: '件', baseUnit: '件', conversionFactor: 10 },
      { packageUnit: '箱', baseUnit: '件', conversionFactor: 0 },
    ])).toBe('');
  });
});

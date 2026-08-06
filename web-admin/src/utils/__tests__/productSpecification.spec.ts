import { describe, expect, it } from 'vitest';
import {
  composeProductSpecification,
  composeProductSpecificationFromNetContent,
  convertNetContent,
  displayProductSpecification,
  parseNetContent,
} from '@/utils/productSpecification';

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

  it('支持重量和容量的同维度等值换算，并拒绝跨维度换算', () => {
    expect(convertNetContent(800, 'g', 'kg')).toBe(0.8);
    expect(convertNetContent(1, 'kg', 'g')).toBe(1000);
    expect(convertNetContent(1000, 'ml', 'L')).toBe(1);
    expect(convertNetContent(1, 'L', 'ml')).toBe(1000);
    expect(convertNetContent(800, 'g', 'ml')).toBeNull();
  });

  it('用中文销售/包装单位生成容量规格，canonical unit 不泄漏', () => {
    expect(composeProductSpecificationFromNetContent(500, 'ml', 'box', [
      { packageUnit: 'case', baseUnit: 'box', conversionFactor: 12 },
    ])).toBe('500ml/盒 12盒/箱 6L/箱');
  });

  it('编辑时从既有规格恢复净含量单位，不用新建默认值覆盖', () => {
    expect(parseNetContent('0.8kg/盒 8盒/箱', 800)).toEqual({ amount: 0.8, unit: 'kg' });
    expect(parseNetContent('500ml/瓶 12瓶/箱', null)).toEqual({ amount: 500, unit: 'ml' });
    expect(parseNetContent('', 800)).toEqual({ amount: 800, unit: 'g' });
  });

  it('只转换规格中的 canonical 包装单位供用户查看', () => {
    expect(displayProductSpecification('800g/box 8box/case 1slice/box')).toBe('800g/盒 8盒/箱 1片/盒');
    expect(displayProductSpecification('1kg/袋')).toBe('1kg/袋');
  });

  /**
   * 2026-08-06 客户报: 六膳门 BBQ猪五花 规格列显示 `1kg/pack 10pack/箱 10kg/箱`。
   * `pack` 在 UNIT_LABELS 里明明有「包」, 只是这条正则当时手抄了 box|case|slice 三个码。
   */
  it('🔴 pack 也要翻 —— 翻译码表由 UNIT_LABELS 推导, 不再手抄', () => {
    expect(displayProductSpecification('1kg/pack 10pack/箱 10kg/箱')).toBe('1kg/包 10包/箱 10kg/箱');
    expect(displayProductSpecification('400g/bag 20bag/箱 8kg/箱')).toBe('400g/袋 20袋/箱 8kg/箱');
    expect(displayProductSpecification('1kg/bottle 6bottle/crate')).toBe('1kg/瓶 6瓶/框');
    expect(displayProductSpecification('100g/tray 10tray/箱')).toBe('100g/托盘 10托盘/箱');
  });

  it('国际计量符号不翻 —— kg 不能变成「公斤」', () => {
    expect(displayProductSpecification('1kg/kg')).toBe('1kg/kg');
    expect(displayProductSpecification('500g/g 250ml/L')).toBe('500g/g 250ml/L');
  });

  it('码嵌在英文单词里不误伤', () => {
    expect(displayProductSpecification('backpack/箱')).toBe('backpack/箱');
    expect(displayProductSpecification('1kg/package')).toBe('1kg/package');
  });
});

import { describe, expect, it } from 'vitest';
import {
  canonicalUnitCode,
  displayUnit,
  formatPriceUnit,
  pricingAmountPreview,
  purchaseOrderPricingPayload,
  resolvePurchaseSuggestionUnits,
  mergeCanonicalUnitOptions,
  sameUnit,
} from '../unitPricing';

describe('unitPricing', () => {
  /**
   * 🔴 #1672 回归网。件/个/只 都归一到 `pcs`, 但**互相不是同一个单位**
   * (`sameUnit` 的 DISTINCT_COUNT_LABELS: 一只鸡不是一件包材)。
   * 展示时若走一遍规范码再取 label, 三个会被渲染成同一个字 —— 用户填「件」
   * 的产品界面上变成「只」。#1672 正是这么回归的, 红了 8 天没人看见。
   */
  it('计数单位展示不得把一个中文标签改写成另一个', () => {
    expect(displayUnit('件')).toBe('件');
    expect(displayUnit('个')).toBe('个');
    expect(displayUnit('只')).toBe('只');
  });

  /**
   * 通用不变式: **用户填的单位, 展示出来必须还是同一个单位**。
   * 这条比上面三行更耐用 —— 以后再往 UNIT_LABELS 里改任何一条,
   * 只要它把某个单位渲染成了「别的单位」, 这里就会红。
   */
  it('displayUnit 的产物必须与输入是同一个单位', () => {
    for (const unit of ['件', '个', '只', '盒', '箱', '袋', '片', 'box', 'case', 'bag', 'slice']) {
      expect(sameUnit(unit, displayUnit(unit))).toBe(true);
    }
  });

  /**
   * ⚠️ **已知未决口径**, 钉在这里防止它再次静默漂移。
   *
   * 裸规范码 `pcs` 目前展示成「只」(#1672 对齐系统单位表的 `unitName`), 但同文件的
   * `sameUnit` 把「只」列进 DISTINCT_COUNT_LABELS = 「只」不是 `pcs` ——
   * 于是 `sameUnit('pcs', displayUnit('pcs')) === false`: **一个单位不等于它自己的展示标签**。
   * 上面那条不变式因此不能覆盖裸码。
   *
   * 按本文件注释的说法 (「件」就是 pcs 的中文名) 应该显示「件」; 按系统单位表应该显示「只」。
   * 这是产品口径问题, 需要 Steve 拍板后再改, 改的时候把这条用例一起改掉。
   */
  it('裸 pcs 的展示口径未决 —— 当前显示「只」, 且与 sameUnit 自相矛盾', () => {
    expect(displayUnit('pcs')).toBe('只');
    expect(sameUnit('pcs', displayUnit('pcs'))).toBe(false);
  });

  it('normalizes canonical aliases without changing the price value', () => {
    expect(canonicalUnitCode('公斤')).toBe('kg');
    expect(canonicalUnitCode('盒')).toBe('box');
    expect(displayUnit('box')).toBe('盒');
    expect(canonicalUnitCode('箱')).toBe('case');
    expect(displayUnit('case')).toBe('箱');
    expect(canonicalUnitCode('片')).toBe('slice');
    expect(displayUnit('slice')).toBe('片');
    expect(canonicalUnitCode('g')).toBe('g');
    expect(canonicalUnitCode('kg')).toBe('kg');
    expect(displayUnit('g')).toBe('g');
    expect(displayUnit('kg')).toBe('kg');
    expect(canonicalUnitCode('pcs:只')).toBe('pcs');
    expect(displayUnit('pcs:只')).toBe('只');
    expect(displayUnit('pcs')).toBe('只');
    expect(formatPriceUnit('box')).toBe('元/盒');
    expect(formatPriceUnit('case')).toBe('元/箱');
    expect(formatPriceUnit('slice')).toBe('元/片');
    expect(formatPriceUnit('g')).toBe('元/g');
    expect(formatPriceUnit('kg')).toBe('元/kg');
  });

  it('builds price-unit options from material/catalog values without a static whitelist', () => {
    expect(mergeCanonicalUnitOptions(['kg', '袋', 'mL'], 'CUSTOM_TRAY', 'bag'))
      .toEqual(['kg', 'bag', 'mL', 'CUSTOM_TRAY']);
  });

  it('uses backend lineAmount before any client calculation', () => {
    expect(pricingAmountPreview({ quantity: 100_000, quantityUnit: 'g', unitPrice: 30, priceUnit: 'kg', lineAmount: 3_000 }))
      .toEqual({ amount: 3_000, source: 'backend-line-amount', message: '' });
  });

  it('uses backend convertedPricingQuantity for different units', () => {
    expect(pricingAmountPreview({ quantity: 100_000, quantityUnit: 'g', unitPrice: 30, priceUnit: 'kg', convertedPricingQuantity: 100 }).amount)
      .toBe(3_000);
  });

  it('fails closed when units differ and no authoritative conversion exists', () => {
    expect(pricingAmountPreview({ quantity: 100_000, quantityUnit: 'g', unitPrice: 30, priceUnit: 'kg' }))
      .toEqual({ amount: null, source: 'pending', message: '保存后由系统换算' });
  });

  it('allows direct multiplication only when canonical units match', () => {
    expect(pricingAmountPreview({ quantity: 2, quantityUnit: '盒', unitPrice: 15, priceUnit: 'box' }).amount).toBe(30);
  });

  it('keeps referencePriceUnit as the PO priceUnit when suggestion quantity is in another unit', () => {
    const suggestion = {
      quantity: 1000,
      unit: 'g',
      quantityUnit: 'g',
      referenceUnitPrice: 20,
      referencePriceUnit: 'kg',
      priceUnit: 'g',
    };

    expect(resolvePurchaseSuggestionUnits(suggestion)).toEqual({ quantityUnit: 'g', priceUnit: 'kg' });
    expect(purchaseOrderPricingPayload(suggestion)).toMatchObject({
      unit: 'g',
      quantityUnit: 'g',
      priceUnit: 'kg',
    });
  });
});

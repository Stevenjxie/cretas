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
   * ✅ **口径已定案 (2026-08-01)**: 裸规范码 `pcs` 展示成「件」。
   *
   * 判据不是偏好, 是**两侧同源规则自己给出的**: 前端 `DISTINCT_COUNT_LABELS` 与后端
   * `UnitContractServiceImpl.DISTINCT_COUNT_LABELS` 都是 `{只, 个}` —— 两边都把「件」
   * 排除在外, 即两边都认定 **件 ≡ pcs**。所以把 pcs 渲染成「只」等于断言 只 ≡ pcs,
   * 与该规则直接矛盾(旧行为下 `sameUnit('pcs', displayUnit('pcs'))` 是 **false**,
   * 一个单位不等于它自己的展示标签)。Java 侧 `UnitDisplayNames` 同样是「件」。
   *
   * 这条现在钉的是**不变式**而不是当前值: 展示标签必须与它自己的规范码互认。
   */
  it('裸 pcs 展示成「件」, 且不再与 sameUnit 自相矛盾', () => {
    expect(displayUnit('pcs')).toBe('件');
    expect(sameUnit('pcs', displayUnit('pcs'))).toBe(true);
  });

  /**
   * 🔴 用户手填的 只/个/件 必须**原样保留** —— 这是 #1672 造成、#2097 修掉的线上缺陷
   * (用户填「件」的产品界面上显示成「只」)。上面改 `UNIT_LABELS.pcs` 只影响裸码,
   * 绝不能把这条一起带翻。
   */
  it('用户手填的 只/个/件 原样保留, 不被 pcs 口径影响', () => {
    expect(displayUnit('只')).toBe('只');
    expect(displayUnit('个')).toBe('个');
    expect(displayUnit('件')).toBe('件');
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
    // 遗留复合写法自带用户填的标签, 必须原样保留(不受 pcs 口径变更影响)
    expect(displayUnit('pcs:只')).toBe('只');
    // 裸规范码走 2026-08-01 定案的「件」
    expect(displayUnit('pcs')).toBe('件');
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

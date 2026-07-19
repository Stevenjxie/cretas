import { describe, expect, it } from 'vitest';
import {
  canonicalUnitCode,
  displayUnit,
  formatPriceUnit,
  pricingAmountPreview,
  purchaseOrderPricingPayload,
  resolvePurchaseSuggestionUnits,
  mergeCanonicalUnitOptions,
} from '../unitPricing';

describe('unitPricing', () => {
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

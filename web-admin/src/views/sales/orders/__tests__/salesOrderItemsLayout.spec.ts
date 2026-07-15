import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');

describe('sales order item editor layout', () => {
  it('provides an explicit horizontally scrollable workspace for dense line items', () => {
    expect(source).toContain('class="order-items-scroll"');
    expect(source).toContain('class="order-items-grid"');
    expect(source).toContain('overflow-x: auto');
    expect(source).toContain('min-width: 1580px');
    expect(source).toContain('class="sticky-col sticky-product"');
    expect(source).toContain('.sticky-col { position: sticky;');
  });

  it('inherits the SKU base unit and exposes packaging choices for base-unit orders', () => {
    expect(source).toContain('item.unit = pu || item.unit');
    expect(source).not.toContain("pu === '盒' ? '份'");
    expect(source).toContain('spec.baseUnit === item.unit');
  });

  it('blocks saving when packaging specifications failed to load', () => {
    expect(source).toContain('packagingLoadError?: boolean');
    expect(source).toContain('包装规格加载失败，请重试后再创建订单');
    expect(source).toContain('item.packagingLoadError = true');
  });

  it('ignores stale packaging responses after the row switches SKU', () => {
    expect(source).toContain('packagingRequestSequence');
    expect(source).toContain('packagingRequestSequence.get(item) !== requestSequence');
    expect(source).toContain('item.productTypeId !== productId');
  });
});

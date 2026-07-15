import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');

describe('finished-goods opening form UX', () => {
  it('does not fabricate a quantity or unit before a SKU is selected', () => {
    expect(source).toContain('producedQuantity: null');
    expect(source).toContain("unit: ''");
    expect(source).toContain(':disabled="!openingForm.productTypeId"');
  });

  it('requires a box specification even when inventory is entered in the SKU base unit', () => {
    expect(source).toContain('spec.baseUnit === openingForm.value.unit');
  });

  it('blocks opening inventory when packaging specifications failed to load', () => {
    expect(source).toContain('openingPackagingLoadFailed');
    expect(source).toContain('包装规格加载失败，请重试后再入库');
  });

  it('ignores stale packaging responses after the opening SKU changes', () => {
    expect(source).toContain('openingPackagingLoadSeq');
    expect(source).toContain('requestSeq !== openingPackagingLoadSeq');
    expect(source).toContain('openingForm.value.productTypeId !== productTypeId');
  });
});

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../index.vue'),
  'utf8',
);

describe('SKU edit hydration source contract', () => {
  it('hydrates the edit form from the authoritative product detail together with packaging specs', () => {
    expect(source).toContain('Promise.all([');
    expect(source).toContain('`/${factoryId.value}/product-types/${row.id}`');
    expect(source).toContain('const product = detailResponse.data;');
    expect(source).toContain('formData.gramsPerUnit = product.gramsPerUnit ?? undefined;');
    expect(source).toContain('spec.baseUnit || product.unit');
  });

  it('does not leave the dialog in editing mode when either authoritative request fails', () => {
    expect(source).toContain("ElMessage.error('产品详情或包装规格加载失败，请重试后再编辑产品')");
    expect(source).toContain('isEditing.value = false;');
  });
});

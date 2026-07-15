import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../index.vue'), 'utf8');

describe('SKU editor dialog UX', () => {
  it('keeps conversion labels readable and primary actions visible', () => {
    expect(source).toContain('class="product-edit-dialog"');
    expect(source).toContain('label-width="120px"');
    expect(source).toContain(':modal-class="\'product-edit-modal\'"');
    expect(source).toContain('.product-edit-modal .el-dialog__footer');
    expect(source).toContain('position: sticky');
    expect(source).toContain('aria-label="上移工序"');
    expect(source).toContain('aria-label="下移工序"');
    expect(source).toContain('aria-label="移除工序"');
    expect(source).toContain('class="standard-weight-row"');
    expect(source).toContain("1 {{ formData.unit || '基本单位' }} =");
    expect(source).not.toContain('标准单位换算（1${formData.unit');
  });
});

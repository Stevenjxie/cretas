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

  it('keeps semi-finished base units editable and exposes a real preview-first import flow', () => {
    expect(source).toContain('if (!formData.unit) formData.unit = \'kg\'');
    expect(source).not.toContain(':disabled="isSemiFinishedSku"');
    expect(source).toContain('v-model="importDialogVisible"');
    expect(source).toContain('/product-types/import/template');
    expect(source).toContain('/product-types/import/preview');
    expect(source).toContain('/product-types/import/confirm');
    expect(source).toContain('/upload/product-image');
    expect(source).toContain("formData.append('imageMappings', JSON.stringify(allMappings))");
    expect(source).toContain('accept=".xlsx"');
    expect(source).not.toContain('accept=".xlsx,.xls"');
    expect(source).toContain('prop="specification" label="生成规格"');
    expect(source).toContain("{{ row.specification || '—' }}");
  });
});

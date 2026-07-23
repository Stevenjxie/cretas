import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import UnitSelect from '../UnitSelect.vue';

const source = readFileSync(resolve(import.meta.dirname, '../UnitSelect.vue'), 'utf8');
const apiSource = readFileSync(resolve(import.meta.dirname, '../../../api/systemUnits.ts'), 'utf8');
const productSource = readFileSync(resolve(import.meta.dirname, '../../../views/system/products/index.vue'), 'utf8');
const processSource = readFileSync(resolve(import.meta.dirname, '../../../views/system/work-processes/index.vue'), 'utf8');

describe('shared UnitSelect contract', () => {
  it('compiles as a Vue component', () => {
    expect(UnitSelect).toBeTruthy();
  });

  it('offers dictionary-backed create-first behavior without allow-create ghost values', () => {
    expect(apiSource).toContain('/system-config/units');
    expect(source).toContain('＋ 新增单位「${query.trim()}」');
    expect(source).toContain('findDuplicateUnit(units.value, [form.unitName, form.unitSymbol])');
    expect(source).toContain('form.unitCode = generatedUnitCode(form.unitName.trim())');
    expect(source).toContain('单位代码由系统自动生成');
    expect(source).not.toContain('<el-form-item label="单位代码"');
    expect(source).toContain('创建并选中');
    expect(source).toContain('await loadUnits();');
    expect(source).toContain('单位已存在，已选择');
    expect(source).not.toContain('allow-create');
  });

  it('is shared by SKU while work-process master data no longer owns port units', () => {
    expect(productSource).toContain('<UnitSelect');
    expect(productSource).toContain('label="基础规格"');
    expect(productSource).toContain('placeholder="选择销售单位"');
    expect(productSource).toContain(':show-symbol="false"');
    expect(productSource).toContain("每 1 {{ displayUnit(formData.unit) || '销售单位' }}");
    expect(processSource).not.toContain('<UnitSelect');
    expect(processSource).not.toContain('label="投入单位"');
    expect(processSource).not.toContain('label="产出单位"');
  });

  it('never emits the historic display label as part of the canonical unit code', () => {
    expect(apiSource).toContain("{ unitCode: 'pcs', unitName: '只'");
    expect(apiSource).not.toContain("unitCode: 'pcs:只'");
    expect(source).toContain('canonicalSystemUnitCode(current?.unitCode || props.modelValue)');
    expect(source).toContain('canonicalSystemUnitCode(findDuplicateUnit(activeUnits.value, [value])?.unitCode || value)');
  });
});

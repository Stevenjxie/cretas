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
    expect(source).toContain('findDuplicateUnit(units.value, [form.unitCode, form.unitName, form.unitSymbol])');
    expect(source).toContain('创建并选中');
    expect(source).not.toContain('allow-create');
  });

  it('is shared by SKU and both work-process unit ports', () => {
    expect(productSource).toContain('<UnitSelect');
    expect(productSource).toContain(':placeholder="gramsPerUnitPlaceholder"');
    expect(processSource.match(/<UnitSelect/g)).toHaveLength(2);
    expect(processSource).toContain('placeholder="搜索投入单位；无匹配可新增"');
    expect(processSource).toContain('@change="markOutputUnitEdited"');
  });
});

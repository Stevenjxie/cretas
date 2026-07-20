import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { displayUnit } from '@/utils/unitPricing';

const pageSource = readFileSync(
  resolve(process.cwd(), 'src/views/inventory/by-warehouse/index.vue'),
  'utf8',
);

describe('BUG-F006-M09-INV-UNIT-001 分仓库存单位显示契约', () => {
  it.each([
    ['box', '\u76d2'],
    ['case', '\u7bb1'],
    ['slice', '\u7247'],
    ['g', 'g'],
    ['kg', 'kg'],
  ])('将 canonical 单位 %s 显示为 %s', (canonical, expected) => {
    expect(displayUnit(canonical)).toBe(expected);
  });

  it('原料和成品单位均通过 displayUnit 渲染且不改写 API 字段', () => {
    expect(pageSource).toContain("import { displayUnit } from '@/utils/unitPricing';");
    expect(pageSource).toContain("{{ displayUnit(row.quantityUnit || row.unit) || '-' }}");
    expect(pageSource).toContain("{{ displayUnit(row.unit) || '-' }}");
    expect(pageSource).not.toContain('<el-table-column prop="unit" label="单位"');
  });
});

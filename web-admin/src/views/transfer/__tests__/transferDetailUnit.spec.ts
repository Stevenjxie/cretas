import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { displayUnit } from '@/utils/unitPricing';

const detailSource = readFileSync(
  resolve(process.cwd(), 'src/views/transfer/detail.vue'),
  'utf8',
);

describe('M08 调拨详情单位显示契约', () => {
  it.each([
    ['box', '盒'],
    ['case', '箱'],
    ['slice', '片'],
    ['g', 'g'],
    ['kg', 'kg'],
  ])('只转换用户可见单位 %s -> %s', (canonical, expected) => {
    expect(displayUnit(canonical)).toBe(expected);
  });

  it('详情、操作确认和差异处理统一经过 displayUnit', () => {
    expect(detailSource).toContain("import { displayUnit } from '@/utils/unitPricing';");
    expect(detailSource).toContain('${displayUnit(it.unit)}');
    expect(detailSource).toContain('{{ displayUnit(row.unit) }}');
    expect(detailSource).toContain('{{ displayUnit(decidingDiff.unit) }}');
    expect(detailSource).not.toContain('prop="unit" label="单位"');
    expect(detailSource).not.toContain('{{ row.shippedQuantity }} {{ row.unit }}');
    expect(detailSource).not.toContain('{{ row.receivedQuantity }} {{ row.unit }}');
    expect(detailSource).not.toContain('{{ decidingDiff.shippedQuantity }} {{ decidingDiff.unit }}');
    expect(detailSource).not.toContain('{{ decidingDiff.receivedQuantity }} {{ decidingDiff.unit }}');
  });
});

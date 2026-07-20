import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const profitSource = readFileSync(resolve(import.meta.dirname, '../profit-detail.vue'), 'utf8');

describe('sales order detail display-unit contract', () => {
  it('renders canonical order and delivery units through displayUnit', () => {
    expect(detailSource).toContain('{{ displayUnit(row.unit) }}');
    expect(detailSource).toContain('{{ item.deliveredQuantity }}{{ displayUnit(item.unit) }}');
    expect(detailSource).toContain('{{ row.availableQuantity }}{{ displayUnit(row.unit || item.unit) }}');
    expect(detailSource).not.toContain('<el-table-column prop="unit" label="单位"');
  });

  it('keeps source-warehouse payload canonical while rendering the configured warehouse name', () => {
    expect(detailSource).toContain('sourceWarehouseCode: it.sourceWarehouseCode ||');
    expect(detailSource).toContain('sourceWarehouseLabel(row.sourceWarehouseCode)');
  });

  it('covers subsequent quick-delivery and profit displays without changing payload values', () => {
    expect(listSource).toContain('{{ displayUnit(item.unit) }}');
    expect(profitSource).toContain('{{ displayUnit(row.unit) }}');
    expect(listSource).toContain('unit: canonicalUnitCode(item.unit');
  });
});

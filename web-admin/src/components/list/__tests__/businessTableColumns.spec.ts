import { beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import {
  normalizeVisibleColumnKeys,
  readVisibleColumnKeys,
} from '../businessTableColumns';

describe('business list column contract', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('keeps only known unique columns and enforces the configured maximum', () => {
    expect(normalizeVisibleColumnKeys(
      ['date', 'unknown', 'date', 'amount', 'source'],
      ['date', 'amount', 'source'],
      ['date'],
      2,
    )).toEqual(['date', 'amount']);
  });

  it('falls back to valid defaults when persisted selection is empty or malformed', () => {
    window.localStorage.setItem('columns', '{broken json');
    expect(readVisibleColumnKeys(
      'columns',
      ['date', 'amount', 'source'],
      ['date', 'source'],
      2,
    )).toEqual(['date', 'source']);
  });

  it('drops price columns that are no longer available after a permission change', () => {
    window.localStorage.setItem('columns', JSON.stringify(['date', 'amount']));
    expect(readVisibleColumnKeys(
      'columns',
      ['date', 'source'],
      ['date', 'source'],
      2,
    )).toEqual(['date']);
  });
});

describe('sales, procurement and production list integration', () => {
  const salesSource = readSource('../../../views/sales/orders/list.vue');
  const procurementSource = readSource('../../../views/procurement/orders/list.vue');
  const productionSource = readSource('../../../views/production/plans/list.vue');

  it.each([
    ['sales', salesSource],
    ['procurement', procurementSource],
    ['production', productionSource],
  ])('%s list uses the shared fixed-grid and limited column selector', (_name, source) => {
    expect(source).toContain('business-list-table');
    expect(source).toContain('<ListColumnSelector');
    expect(source).toContain('table-layout="fixed"');
  });

  it('removes unexplained row markers and only shows sales/production selection in batch mode', () => {
    expect(salesSource).not.toContain('<RowMarkerCell');
    expect(procurementSource).not.toContain('<RowMarkerCell');
    expect(procurementSource).not.toContain('type="selection"');
    expect(salesSource).toContain('v-if="salesBatchMode" type="selection"');
    expect(productionSource).toContain('v-if="productionBatchMode" type="selection"');
  });

  it('keeps the procurement fixed columns sortable/filterable without adding bulk controls', () => {
    expect(procurementSource).toContain('prop="orderNumber" label="订单编号" width="170" sortable');
    expect(procurementSource).toContain(':filters="purchaseTypeColumnFilters"');
    expect(procurementSource).toContain('prop="orderDate"');
    expect(procurementSource).toContain('label="总金额"');
    expect(procurementSource).toContain(':filters="purchaseStatusColumnFilters"');
  });
});

function readSource(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8');
}

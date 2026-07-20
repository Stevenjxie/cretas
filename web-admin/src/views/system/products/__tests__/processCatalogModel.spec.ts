import { describe, expect, it } from 'vitest';
import { filterProcessCatalog, pageProcessCatalog, type ProcessCatalogItem } from '../processCatalogModel';

const items: ProcessCatalogItem[] = [
  { id: 'P-2', processName: '定量包装', processCategory: '包装', unit: 'kg', outputUnit: 'box', sortOrder: 2 },
  { id: 'P-1', processName: '修油', processCategory: '前处理', unit: 'kg', outputUnit: 'kg', sortOrder: 1 },
  { id: 'P-2', processName: '重复项', processCategory: '包装', unit: 'kg', outputUnit: 'box', sortOrder: 9 },
  { id: 'P-3', processName: '切片', processCategory: '切配', unit: 'kg', outputUnit: 'slice', description: '薄片' },
];

describe('SKU quick process catalog model', () => {
  it('deduplicates and keeps linked processes first', () => {
    const result = filterProcessCatalog(items, new Set(['P-2']), {
      keyword: '', category: '', outputUnit: '', relation: 'ALL',
    });
    expect(result.map((item) => item.id)).toEqual(['P-2', 'P-1', 'P-3']);
  });

  it('searches by name, code, category tag and description and applies real filters', () => {
    const linked = new Set(['P-2']);
    expect(filterProcessCatalog(items, linked, { keyword: 'P-1', category: '', outputUnit: '', relation: 'ALL' })).toHaveLength(1);
    expect(filterProcessCatalog(items, linked, { keyword: '包装', category: '', outputUnit: '', relation: 'ALL' })[0].id).toBe('P-2');
    expect(filterProcessCatalog(items, linked, { keyword: '薄片', category: '切配', outputUnit: 'slice', relation: 'UNLINKED' })[0].id).toBe('P-3');
    expect(filterProcessCatalog(items, linked, { keyword: '', category: '', outputUnit: '', relation: 'LINKED' })[0].id).toBe('P-2');
  });

  it('paginates the filtered catalog without mutating source order', () => {
    expect(pageProcessCatalog(['a', 'b', 'c'], 2, 2)).toEqual(['c']);
    expect(items[0].id).toBe('P-2');
  });
});

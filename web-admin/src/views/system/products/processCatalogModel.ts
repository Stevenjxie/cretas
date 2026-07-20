export type ProcessRelationFilter = 'ALL' | 'LINKED' | 'UNLINKED';

export interface ProcessCatalogItem {
  id: string;
  processName: string;
  processCategory: string;
  description?: string | null;
}

export interface ProcessCatalogQuery {
  keyword: string;
  category: string;
  relation: ProcessRelationFilter;
}

function matchesKeyword(item: ProcessCatalogItem, keyword: string): boolean {
  const normalized = keyword.trim().toLocaleLowerCase();
  if (!normalized) return true;
  return [item.processName, item.id, item.processCategory, item.description]
    .some((value) => String(value || '').toLocaleLowerCase().includes(normalized));
}

export function filterProcessCatalog(
  items: ProcessCatalogItem[],
  linkedIds: ReadonlySet<string>,
  query: ProcessCatalogQuery,
): ProcessCatalogItem[] {
  const unique = Array.from(new Map(items.map((item) => [item.id, item])).values());
  return unique
    .filter((item) => matchesKeyword(item, query.keyword))
    .filter((item) => !query.category || item.processCategory === query.category)
    .filter((item) => query.relation === 'ALL'
      || (query.relation === 'LINKED' ? linkedIds.has(item.id) : !linkedIds.has(item.id)))
    .sort((left, right) => {
      const relationOrder = Number(linkedIds.has(right.id)) - Number(linkedIds.has(left.id));
      if (relationOrder !== 0) return relationOrder;
      return left.processName.localeCompare(right.processName, 'zh-CN');
    });
}

export function pageProcessCatalog<T>(items: T[], page: number, pageSize: number): T[] {
  const safePage = Math.max(1, Math.trunc(page));
  const safePageSize = Math.max(1, Math.trunc(pageSize));
  return items.slice((safePage - 1) * safePageSize, safePage * safePageSize);
}

export type TopTab = 'analysis' | 'query';
const VALID: TopTab[] = ['analysis', 'query'];
function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : undefined;
  }
  return typeof value === 'string' ? value : undefined;
}
/** 从 route.query 解析顶层 tab (AI探索: 上传分析 / 问数据)。非法值降级 analysis。 */
export function resolveTopTab(query: Record<string, unknown>): TopTab {
  const tab = firstQueryValue(query?.tab);
  if (VALID.includes(tab as TopTab)) return tab as TopTab;

  const mode = firstQueryValue(query?.mode);
  if (mode === 'chat' || mode === 'query') return 'query';
  if (mode === 'analysis' || mode === 'upload') return 'analysis';

  return 'analysis';
}

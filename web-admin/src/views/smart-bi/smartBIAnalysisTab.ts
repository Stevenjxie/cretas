export type TopTab = 'analysis' | 'query';
const VALID: TopTab[] = ['analysis', 'query'];
/** 从 route.query 解析顶层 tab (AI探索: 上传分析 / 问数据)。非法值降级 analysis。 */
export function resolveTopTab(query: Record<string, unknown>): TopTab {
  const t = query?.tab;
  return VALID.includes(t as TopTab) ? (t as TopTab) : 'analysis';
}

/**
 * WS4 Task 3/4 — 经营分析 hub redirect 构造器 (保书签)。
 *
 * 旧的散落页 (销售/趋势/KPI/财务/指标中心) 合并进 /smart-bi/analysis-hub 后, 旧路径
 * 用 function-form redirect 桥接, **必须保留 incoming query** (e.g. AI 快捷入口的 ?q=,
 * 或 FinanceAnalysis 内部 ?tab=cost)。这层是纯函数, 方便单测。
 *
 * 与 smartBIRedirects (smartbi.ts) 的 `(to) => ({path, query})` 同模式。
 */
import type { LocationQuery, LocationQueryRaw } from 'vue-router';

export const ANALYSIS_HUB_PATH = '/smart-bi/analysis-hub';

export interface RedirectTarget {
  path: string;
  query: LocationQueryRaw;
}

/**
 * 构造一个保留 incoming query 并叠加 `tab` (可选 `sub`) 的 hub redirect 目标。
 * tab=null/undefined → 不带 tab (落到 hub 默认 finance tab), 用于"删除页"redirect 到 hub 首页。
 */
export function buildHubRedirect(
  to: { query: LocationQuery },
  tab?: string | null,
  sub?: string | null,
): RedirectTarget {
  const query: LocationQueryRaw = { ...to.query };
  if (tab) query.tab = tab;
  if (sub) query.sub = sub;
  return { path: ANALYSIS_HUB_PATH, query };
}

/** 构造一个保留 incoming query 的简单 path redirect (用于删除页 → dashboard)。 */
export function buildPathRedirect(
  to: { query: LocationQuery },
  path: string,
): RedirectTarget {
  return { path, query: { ...to.query } };
}

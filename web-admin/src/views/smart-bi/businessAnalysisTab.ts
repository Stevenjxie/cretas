/**
 * WS4 Task 2 — BusinessAnalysisHub tab 解析。
 *
 * 经营分析模块 (合并 财务/销售/趋势/KPI) 用 el-tabs 承载 4 个子视图; ?tab= query
 * 同步 (保书签 + 旧路径 redirect 落点)。这层是纯函数, 方便单测 (与
 * financeDashboardSection.ts 同模式)。
 */
export type BusinessAnalysisTab = 'finance' | 'sales' | 'trend' | 'kpi';

export const BUSINESS_ANALYSIS_TABS: BusinessAnalysisTab[] = ['finance', 'sales', 'trend', 'kpi'];

/** 默认 tab = 财务 (与原"财务看板"为合并前最常用入口一致)。 */
export const DEFAULT_BUSINESS_ANALYSIS_TAB: BusinessAnalysisTab = 'finance';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 finance。
 */
export function resolveBusinessAnalysisTab(query: Record<string, unknown> | null | undefined): BusinessAnalysisTab {
  const t = query?.tab;
  return BUSINESS_ANALYSIS_TABS.includes(t as BusinessAnalysisTab)
    ? (t as BusinessAnalysisTab)
    : DEFAULT_BUSINESS_ANALYSIS_TAB;
}

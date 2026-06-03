export type FinanceSection = 'dashboard' | 'analysis';
const VALID: FinanceSection[] = ['dashboard', 'analysis'];

/**
 * 财务看板 section: PBI 看板(dashboard) / 财务数据分析(analysis)。读 ?section= (与 FinanceAnalysis 内部 ?tab= 不冲突)。
 *
 * 默认值按租户业态区分 (修 #10 — qhj 等餐饮租户):
 * - PBI 看板 (dashboard) 是 upload/period 绑定的, 需手动点「生成看板」, 且 PeriodSelector 无「全部历史」选项
 *   → 餐饮租户落在空页, 必须手动操作。
 * - 财务数据分析 (analysis) 已被 WS4 改成默认全部历史 gold 数据 (resolveAllHistoryRange), 进页即见真实数据。
 * 所以餐饮租户 (RESTAURANT) 默认落到 analysis; 工厂租户保持原有 dashboard 默认。
 *
 * 显式且合法的 ?section= 永远优先 (保书签 / 旧路径 redirect 落点 / 工厂用户主动选看板)。
 */
export function resolveFinanceSection(
  query: Record<string, unknown>,
  factoryType?: string | null,
): FinanceSection {
  const t = query?.section;
  if (VALID.includes(t as FinanceSection)) return t as FinanceSection;
  // 无显式 section: 餐饮租户默认 gold 全历史分析页, 工厂租户默认 PBI 看板。
  return factoryType === 'RESTAURANT' ? 'analysis' : 'dashboard';
}

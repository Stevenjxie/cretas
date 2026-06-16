export type FinanceSection = 'dashboard' | 'analysis';
const VALID: FinanceSection[] = ['dashboard', 'analysis'];

/**
 * 财务看板 section: PBI 看板(dashboard) / 财务数据分析(analysis)。读 ?section= (与 FinanceAnalysis 内部 ?tab= 不冲突)。
 *
 * 默认值按租户业态区分:
 * - PBI 看板 (dashboard) 是 upload/period 绑定的, 需手动点「生成看板」, 且 PeriodSelector 无「全部历史」选项
 *   → 无 upload_id 时落空态「请选择数据源」, 对 demo/新工厂租户体验差。
 * - 财务数据分析 (analysis) 已被 WS4 改成默认全部历史 gold 数据 (resolveAllHistoryRange), 进页即见真实数据。
 * 所以餐饮租户 (RESTAURANT) 与工厂租户 (FACTORY) 默认都落到 analysis (进页即有数据);
 * PBI 看板仍可通过显式 ?section=dashboard 访问。
 *
 * 显式且合法的 ?section= 永远优先 (保书签 / 旧路径 redirect 落点 / 用户主动选看板)。
 */
export function resolveFinanceSection(
  query: Record<string, unknown>,
  factoryType?: string | null,
): FinanceSection {
  const t = query?.section;
  if (VALID.includes(t as FinanceSection)) return t as FinanceSection;
  // 无显式 section: 所有租户默认 gold 全历史分析页 (进页即有数据, 无空态)。
  // PBI 看板需要 upload_id, 通过 ?section=dashboard 显式跳入。
  void factoryType; // 保留参数供将来差异化扩展, 当前统一 analysis。
  return 'analysis';
}

/**
 * 是否在「财务 PBI 看板」(dashboard) 子页渲染 Gold 财务全量视图 (follow-up — fu2)。
 *
 * 背景: PBI 看板 (FinancialDashboardPBI) 是 upload_id 绑定的 — 餐饮租户没有上传财务
 * Excel, 进 PBI 看板子页只能看到「请选择数据源」空态 + 需手动点「生成看板」。餐饮租户
 * 真实经营数据在 Gold 全量层 (agg_daily / agg_monthly), 经营驾驶舱 / KPI 看板已渲染。
 *
 * 修法: 餐饮租户 (RESTAURANT) 在 dashboard 子页 → 自动渲染 Gold 财务全量视图
 * (KPI 卡 + 月度营收趋势 + 门店排行 + 渠道占比), 免上传 / 免手动生成。
 * 工厂租户保持原有 upload-based PBI 看板 (PeriodSelector + 生成看板) 不变。
 *
 * 纯函数 (无 IO / 无 Vue), 方便单测; 组件把 authStore.factoryType + 当前 sectionTab 注入。
 *
 * @param factoryType  当前租户业态 (RESTAURANT / FACTORY / ...)
 * @param sectionTab   当前激活的财务 section ('dashboard' = PBI 看板, 'analysis' = 财务数据分析)
 */
export function shouldRenderGoldFinanceView(
  factoryType: string | null | undefined,
  sectionTab: FinanceSection,
): boolean {
  return factoryType === 'RESTAURANT' && sectionTab === 'dashboard';
}

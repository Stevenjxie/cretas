export type FinanceSection = 'dashboard' | 'analysis';
const VALID: FinanceSection[] = ['dashboard', 'analysis'];
/** 财务看板 section: PBI 看板(dashboard) / 财务数据分析(analysis)。读 ?section= (与 FinanceAnalysis 内部 ?tab= 不冲突)。默认 dashboard。 */
export function resolveFinanceSection(query: Record<string, unknown>): FinanceSection {
  const t = query?.section;
  return VALID.includes(t as FinanceSection) ? (t as FinanceSection) : 'dashboard';
}

import type { SummaryStat, ListSummaryResponse } from '@/types/listSummary'

/**
 * Formats sticky-footer summary stats into a compact but useful AI context.
 *
 * The footer button is "one table, one analysis" from the user's point of
 * view, but the answer should still start from overall business judgment and
 * then focus on the current table's hit points. This helper keeps that rule
 * consistent across list pages.
 */
export interface AISummaryContextOptions {
  /** Filter values to surface in context (e.g. `{ status: 'APPROVED' }`) */
  filter?: Record<string, string | number | undefined>
  /** Extra freeform note appended at the end */
  note?: string
  /** Human label for the current list/table, if the caller has one. */
  subject?: string
}

export interface AISummaryAnalysisSection {
  title: string
  body: string
}

export interface AISummaryAnalysis {
  source: 'deterministic-list-summary'
  cacheKey: string
  sections: AISummaryAnalysisSection[]
  text: string
}

function formatStatValue(stat: SummaryStat): string {
  const { value, format, unit } = stat
  if (value == null || value === '') return '-'
  const num = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(num)) return String(value)
  switch (format) {
    case 'currency':
      return `${unit ?? '¥'}${num.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
    case 'percent':
      return `${num.toFixed(1)}${unit ?? '%'}`
    case 'number':
      return `${num.toLocaleString()}${unit ?? ''}`
    default:
      return `${num}${unit ?? ''}`
  }
}

function formatFilter(filter?: Record<string, string | number | undefined>): string {
  if (!filter) return ''
  return Object.entries(filter)
    .filter(([_k, v]) => v != null && v !== '' && v !== 'all')
    .map(([k, v]) => `${k}=${v}`)
    .join(', ')
}

function buildStatText(summary: ListSummaryResponse | null | undefined): string {
  if (!summary?.stats?.length) return '当前表没有可用合计指标，需要先说明数据不足，再建议补齐关键字段。'
  return summary.stats.map((s) => `${s.label} ${formatStatValue(s)}`).join(' | ')
}

function buildFocusText(summary: ListSummaryResponse | null | undefined): string {
  const entity = summary?.entityType ? `entity=${summary.entityType}; ` : ''
  if (!summary?.stats?.length) {
    return `${entity}围绕当前表的业务对象，指出缺少哪些金额、数量、比率、状态或时间维度会限制判断。`
  }
  const labels = summary.stats.map((s) => s.label).join('、')
  return `${entity}以 ${labels} 为击中点，解释这些指标对当前表代表的业务动作意味着什么。`
}

function stableFilterKey(filter?: Record<string, string | number | undefined>): string {
  if (!filter) return ''
  return Object.entries(filter)
    .filter(([_k, v]) => v != null && v !== '' && v !== 'all')
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}:${v}`)
    .join('|')
}

function buildCacheKey(
  summary: ListSummaryResponse | null | undefined,
  opts: AISummaryContextOptions,
): string {
  const labels = summary?.stats?.map((s) => s.label).join(',') || 'no-stats'
  return [
    'list-summary',
    opts.subject || summary?.entityType || 'unknown',
    summary?.entityType || 'unknown',
    labels,
    stableFilterKey(opts.filter),
  ].join('::')
}

function pickPrimaryStats(summary: ListSummaryResponse | null | undefined): SummaryStat[] {
  return [...(summary?.stats ?? [])].slice(0, 4)
}

export function buildSummaryAnalysis(
  summary: ListSummaryResponse | null | undefined,
  opts: AISummaryContextOptions = {},
): AISummaryAnalysis {
  const subject = opts.subject || summary?.entityType || '当前列表'
  const stats = pickPrimaryStats(summary)
  const statText = buildStatText(summary)
  const filterText = formatFilter(opts.filter)

  const sections: AISummaryAnalysisSection[] = []

  if (stats.length === 0) {
    sections.push(
      {
        title: '整体判断',
        body: `${subject}目前没有可用合计指标，先不要下结论；应优先补齐金额、数量、状态、日期和责任对象，否则只能看到明细，不能判断规模和优先级。`,
      },
      {
        title: '当前表击中分析',
        body: '当前表最需要先确认字段完整性：是否有日期、数量、金额、状态、门店/档口/供应商/人员等维度。缺少这些字段时，异常只能人工翻单，无法自动聚合。',
      },
      {
        title: '建议动作',
        body: '先补字段和合计口径，再按最近 30 天跑一次汇总；如果仍为空，把空表作为数据接入问题处理，而不是让 AI 生成泛泛建议。',
      },
    )
  } else {
    const labels = stats.map((s) => s.label).join('、')
    const moneyStats = stats.filter((s) => s.format === 'currency')
    const percentStats = stats.filter((s) => s.format === 'percent')
    const numberStats = stats.filter((s) => s.format === 'number' || !s.format)
    const moneySignal = moneyStats.length
      ? `金额类指标（${moneyStats.map((s) => `${s.label} ${formatStatValue(s)}`).join('、')}）要优先看责任归属和发生日期，先找集中发生点。`
      : '金额口径暂不突出，重点先看数量、状态和结构分布。'
    const percentSignal = percentStats.length
      ? `比例类指标（${percentStats.map((s) => `${s.label} ${formatStatValue(s)}`).join('、')}）适合做门店、品类、班次之间的横向比较。`
      : '当前缺少比例口径，建议补充率类指标，避免只看绝对值误判规模。'
    const numberSignal = numberStats.length
      ? `数量类指标（${numberStats.map((s) => `${s.label} ${formatStatValue(s)}`).join('、')}）用于判断是否只是个别单据，还是已经形成批量问题。`
      : ''

    sections.push(
      {
        title: '整体判断',
        body: `${subject}当前统计为：${statText}。先把它当成经营体检的入口，不只复述数字；${moneySignal}${percentSignal}`,
      },
      {
        title: '当前表击中分析',
        body: `这一表应围绕 ${labels} 做击中分析。${numberSignal} 重点找金额/数量背离、状态积压、单一门店或档口集中、同一食材反复出现这四类信号。`,
      },
      {
        title: '建议动作',
        body: '第一，按门店/档口/责任人切一遍，找 Top 3 集中点；第二，拉出对应单据和日期，核查是否由同一批次或同一班次造成；第三，把处理动作回写到备注或审批结论，便于下次复盘。',
      },
      {
        title: '追问方向',
        body: '下一步优先追问：最近 30 天按日期是否集中、按食材/供应商是否集中、按状态是否卡在待处理、按人员/档口是否有重复责任点。',
      },
    )
  }

  if (filterText) {
    sections.splice(1, 0, {
      title: '筛选边界',
      body: `本次只分析筛选条件 ${filterText} 下的数据；结论不能外推到未筛选门店、日期或状态。`,
    })
  }
  if (opts.note) {
    sections.push({ title: '补充约束', body: opts.note })
  }

  const cacheKey = buildCacheKey(summary, opts)
  return {
    source: 'deterministic-list-summary',
    cacheKey,
    sections,
    text: sections.map((s) => `${s.title}: ${s.body}`).join('\n\n'),
  }
}

export function formatSummaryForAI(
  summary: ListSummaryResponse | null | undefined,
  opts: AISummaryContextOptions = {},
): string {
  const filterText = formatFilter(opts.filter)
  const subject = opts.subject || summary?.entityType || '当前列表'
  const sections = [
    `对象: ${subject}${summary?.entityType ? ` (entity=${summary.entityType})` : ''}`,
    filterText ? `筛选: ${filterText}` : '',
    `当前统计: ${buildStatText(summary)}`,
    '整体判断: 先用全部统计判断规模、风险、趋势或结构性异常，不要只复述数字；如果缺少同比/环比/门店/品类维度，要明确说出判断边界。',
    `当前表击中分析: ${buildFocusText(summary)} 找出最值得追问的异常、集中度、金额/数量背离或状态积压。`,
    '建议动作: 给出 2-3 条可执行动作，包含责任对象、核查数据、优先级和预期业务收益。',
    '追问方向: 给出下一步最应该 drill down 的维度，例如门店、品类、供应商、班次、日期、状态或单据明细。',
    opts.note ? `补充约束: ${opts.note}` : '',
  ].filter(Boolean)

  return sections.length > 0 ? ` (${sections.join('; ')})` : ''
}

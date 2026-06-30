import { describe, expect, it } from 'vitest'

import { buildSummaryAnalysis, formatSummaryForAI } from '../aiSummaryContext'
import type { ListSummaryResponse } from '@/types/listSummary'

describe('formatSummaryForAI', () => {
  it('builds constructive overall and focused table-analysis context', () => {
    const summary: ListSummaryResponse = {
      entityType: 'wastage',
      stats: [
        { label: '损耗金额', value: 12880.5, format: 'currency', unit: '¥' },
        { label: '损耗率', value: 4.8, format: 'percent' },
        { label: '异常门店', value: 3, format: 'number' },
      ],
    }

    const context = formatSummaryForAI(summary, {
      filter: { status: 'pending', store: '上海静安店', page: 'all' },
      note: '重点看鲜活食材',
    })

    expect(context).toContain('整体判断')
    expect(context).toContain('当前表击中分析')
    expect(context).toContain('建议动作')
    expect(context).toContain('追问方向')
    expect(context).toContain('entity=wastage')
    expect(context).toContain('status=pending')
    expect(context).toContain('store=上海静安店')
    expect(context).toContain('损耗金额 ¥12,880.50')
    expect(context).toContain('损耗率 4.8%')
    expect(context).toContain('异常门店 3')
    expect(context).toContain('重点看鲜活食材')
    expect(context.length).toBeGreaterThan(180)
  })

  it('builds deterministic one-table analysis with cache key and sections', () => {
    const summary: ListSummaryResponse = {
      entityType: 'wastage',
      stats: [
        { label: '损耗金额', value: 12880.5, format: 'currency', unit: '¥' },
        { label: '损耗率', value: 4.8, format: 'percent' },
        { label: '记录数', value: 30, format: 'number' },
      ],
    }

    const analysis = buildSummaryAnalysis(summary, {
      subject: '损耗记录',
      filter: { status: 'APPROVED' },
    })

    expect(analysis.source).toBe('deterministic-list-summary')
    expect(analysis.cacheKey).toContain('list-summary::损耗记录::wastage')
    expect(analysis.cacheKey).toContain('status:APPROVED')
    expect(analysis.sections.map((s) => s.title)).toContain('整体判断')
    expect(analysis.sections.map((s) => s.title)).toContain('当前表击中分析')
    expect(analysis.sections.map((s) => s.title)).toContain('建议动作')
    expect(analysis.text).toContain('按门店/档口/责任人')
    expect(analysis.text).toContain('金额/数量背离')
  })
})

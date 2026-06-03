import { describe, it, expect } from 'vitest';
import {
  resolveBusinessAnalysisTab,
  BUSINESS_ANALYSIS_TABS,
  DEFAULT_BUSINESS_ANALYSIS_TAB,
} from '../businessAnalysisTab';

describe('businessAnalysisTab — 经营分析 hub tab 解析 (WS4 Task 2)', () => {
  it('4 个 tab: 财务/销售/趋势/KPI', () => {
    expect(BUSINESS_ANALYSIS_TABS).toEqual(['finance', 'sales', 'trend', 'kpi']);
  });

  it('默认 tab = finance', () => {
    expect(DEFAULT_BUSINESS_ANALYSIS_TAB).toBe('finance');
    expect(resolveBusinessAnalysisTab({})).toBe('finance');
    expect(resolveBusinessAnalysisTab(null)).toBe('finance');
    expect(resolveBusinessAnalysisTab(undefined)).toBe('finance');
  });

  it.each(['finance', 'sales', 'trend', 'kpi'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveBusinessAnalysisTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 finance', () => {
    expect(resolveBusinessAnalysisTab({ tab: 'whatever' })).toBe('finance');
    expect(resolveBusinessAnalysisTab({ tab: '' })).toBe('finance');
    expect(resolveBusinessAnalysisTab({ tab: ['sales'] })).toBe('finance');
  });
});

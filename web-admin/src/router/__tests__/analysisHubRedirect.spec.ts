import { describe, it, expect } from 'vitest';
import type { LocationQuery } from 'vue-router';
import { buildHubRedirect, buildPathRedirect, ANALYSIS_HUB_PATH } from '../analysisHubRedirect';

const q = (o: Record<string, string>): LocationQuery => o as unknown as LocationQuery;

describe('analysisHubRedirect — 旧页 → 经营分析 hub redirect (WS4 Task 3/4)', () => {
  it('hub path = /smart-bi/analysis-hub', () => {
    expect(ANALYSIS_HUB_PATH).toBe('/smart-bi/analysis-hub');
  });

  it('销售/趋势/KPI/财务 各 redirect 到对应 tab', () => {
    expect(buildHubRedirect({ query: q({}) }, 'sales')).toEqual({ path: ANALYSIS_HUB_PATH, query: { tab: 'sales' } });
    expect(buildHubRedirect({ query: q({}) }, 'trend')).toEqual({ path: ANALYSIS_HUB_PATH, query: { tab: 'trend' } });
    expect(buildHubRedirect({ query: q({}) }, 'kpi')).toEqual({ path: ANALYSIS_HUB_PATH, query: { tab: 'kpi' } });
    expect(buildHubRedirect({ query: q({}) }, 'finance')).toEqual({ path: ANALYSIS_HUB_PATH, query: { tab: 'finance' } });
  });

  it('指标中心 → tab=kpi + sub=indicator', () => {
    expect(buildHubRedirect({ query: q({}) }, 'kpi', 'indicator')).toEqual({
      path: ANALYSIS_HUB_PATH,
      query: { tab: 'kpi', sub: 'indicator' },
    });
  });

  it('保留 incoming query (e.g. ?q= / ?tab=cost), tab 叠加', () => {
    expect(buildHubRedirect({ query: q({ q: '近30天营收' }) }, 'sales')).toEqual({
      path: ANALYSIS_HUB_PATH,
      query: { q: '近30天营收', tab: 'sales' },
    });
    // FinanceAnalysis 内部 ?tab=cost 等会被外层 tab=finance 覆盖 (key 同名) — 预期行为:
    // hub 用 tab 选一级 tab, 子视图自己的子 tab 用别的 key, 这里 finance 落点正确。
    expect(buildHubRedirect({ query: q({ tab: 'cost' }) }, 'finance')).toEqual({
      path: ANALYSIS_HUB_PATH,
      query: { tab: 'finance' },
    });
  });

  it('tab=null/undefined → 不带 tab (删除页落 hub 首页)', () => {
    expect(buildHubRedirect({ query: q({}) })).toEqual({ path: ANALYSIS_HUB_PATH, query: {} });
    expect(buildHubRedirect({ query: q({ from: 'x' }) }, null)).toEqual({ path: ANALYSIS_HUB_PATH, query: { from: 'x' } });
  });

  it('buildPathRedirect 保留 query (删除页 → dashboard)', () => {
    expect(buildPathRedirect({ query: q({ a: '1' }) }, '/smart-bi/dashboard')).toEqual({
      path: '/smart-bi/dashboard',
      query: { a: '1' },
    });
  });
});

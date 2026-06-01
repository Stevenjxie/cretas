import { describe, it, expect } from 'vitest';
import { resolveTopTab } from '../smartBIAnalysisTab';

describe('SmartBIAnalysis topTab resolution (P3)', () => {
  it('?tab=query → query', () => { expect(resolveTopTab({ tab: 'query' })).toBe('query'); });
  it('?tab=analysis → analysis', () => { expect(resolveTopTab({ tab: 'analysis' })).toBe('analysis'); });
  it('无 tab → 默认 analysis', () => { expect(resolveTopTab({})).toBe('analysis'); });
  it('非法 tab → 默认 analysis', () => { expect(resolveTopTab({ tab: 'garbage' })).toBe('analysis'); });
});

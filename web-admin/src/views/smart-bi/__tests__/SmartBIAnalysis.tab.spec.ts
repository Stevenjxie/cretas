import { describe, expect, it } from 'vitest';
import { resolveTopTab } from '../smartBIAnalysisTab';

describe('SmartBIAnalysis topTab resolution', () => {
  it('resolves ?tab=query to query', () => {
    expect(resolveTopTab({ tab: 'query' })).toBe('query');
  });

  it('resolves ?tab=analysis to analysis', () => {
    expect(resolveTopTab({ tab: 'analysis' })).toBe('analysis');
  });

  it('resolves passwordless demo ?mode=chat deep links to query', () => {
    expect(resolveTopTab({ mode: 'chat' })).toBe('query');
  });

  it('resolves ?mode=query to query', () => {
    expect(resolveTopTab({ mode: 'query' })).toBe('query');
  });

  it('lets tab take precedence over mode', () => {
    expect(resolveTopTab({ tab: 'analysis', mode: 'chat' })).toBe('analysis');
  });

  it('defaults to analysis without a tab or mode', () => {
    expect(resolveTopTab({})).toBe('analysis');
  });

  it('defaults invalid tab and mode values to analysis', () => {
    expect(resolveTopTab({ tab: 'garbage', mode: 'unknown' })).toBe('analysis');
  });
});

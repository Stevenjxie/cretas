import { describe, expect, it } from 'vitest';
import {
  formatFeedPlaceholder,
  formatPlannedOutput,
  formatProcessOutput,
  formatSourceFeedSummary,
  resolveProcessSheetUnits,
  withProcessSheetUnits,
} from '../processSheetUnits';

describe('processSheetUnits', () => {
  it('uses the product-process override as input and the process output unit as output', () => {
    expect(resolveProcessSheetUnits({ unitOverride: '只', defaultUnit: 'kg', defaultOutputUnit: '袋' }))
      .toEqual({ inputUnit: '只', outputUnit: '袋' });
  });

  it('changes only quantity labels, keeping weight-specific fields in kg', () => {
    const cols = withProcessSheetUnits([
      { key: 'before', label: '投入(kg)' },
      { key: 'after', label: '产出(kg)' },
      { key: 'remain', label: '剩余(kg)' },
      { key: 'productWeight', label: '成品重量(kg)' },
    ], { inputUnit: '只', outputUnit: '袋' });
    expect(cols.map((it) => it.label)).toEqual(['投入(只)', '产出(袋)', '剩余(只)', '成品重量(kg)']);
  });

  it('labels plan quantity as finished-product output and keeps its explicit unit', () => {
    expect(formatPlannedOutput(10, 'kg')).toBe('计划成品 10 kg');
    expect(formatPlannedOutput(10, '包')).toBe('计划成品 10 包');
  });

  it('renders process summaries and feed prompts using configured units', () => {
    expect(formatProcessOutput(400, 'bag')).toBe('产出 400.00 bag');
    expect(formatSourceFeedSummary(2, 200, 'each')).toBe('2批 · 200.0each');
    expect(formatFeedPlaceholder('each')).toBe('投料each');
  });
});

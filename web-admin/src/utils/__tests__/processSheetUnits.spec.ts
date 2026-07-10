import { describe, expect, it } from 'vitest';
import { resolveProcessSheetUnits, withProcessSheetUnits } from '../processSheetUnits';

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
});

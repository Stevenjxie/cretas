import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detailSource = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('procurement order tax rate column', () => {
  it('renders the real per-line taxRate field in the detail table', () => {
    expect(detailSource).toContain('prop="taxRate"');
    expect(detailSource).toContain('label="税率"');
    expect(detailSource).toContain('{{ formatTaxRate(row.taxRate) }}');
    expect(detailSource).toContain('v-if="canViewPrice" prop="taxRate"');
  });

  it('formats backend taxRate values without mock defaults', () => {
    expect(detailSource).toContain('function formatTaxRate(rate: unknown): string');
    expect(detailSource).toContain("if (rate == null || rate === '') return '-'");
    expect(detailSource).toContain('const percent = numeric <= 1 ? numeric * 100 : numeric');
    expect(detailSource).not.toContain('mockTaxRate');
    expect(detailSource).not.toContain('13%');
  });
});

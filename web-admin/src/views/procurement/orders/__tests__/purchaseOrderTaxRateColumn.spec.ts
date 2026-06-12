import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const viewDir = resolve(__dirname, '..');

function readView(name: string): string {
  return readFileSync(resolve(viewDir, name), 'utf8');
}

describe('procurement order tax rate column', () => {
  it('shows tax rate on the internal price table only', () => {
    const source = readView('detail.vue');

    expect(source).toContain('prop="taxRate"');
    expect(source).toContain('label="税率"');
    expect(source).toContain('formatTaxRate(row.taxRate)');
    expect(source).toContain('v-if="canViewPrice" prop="taxRate"');
  });

  it('formats backend tax rate values without a hard-coded default rate', () => {
    const source = readView('detail.vue');

    expect(source).toContain('function formatTaxRate(rate: unknown): string');
    expect(source).toContain('numeric <= 1 ? numeric * 100 : numeric');
    expect(source).not.toContain('13%');
  });
});

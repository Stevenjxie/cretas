import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('BOM item unit contract wiring', () => {
  it('displays localized labels while submitting the canonical material unit', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/production/bom/index.vue'), 'utf8');

    expect(source).toContain('const quantityUnit = canonicalUnitCode(bomForm.value.quantityUnit || bomForm.value.unit)');
    expect(source).toContain('unit: quantityUnit');
    expect(source).toContain(':label="displayUnit(unit)"');
  });
});

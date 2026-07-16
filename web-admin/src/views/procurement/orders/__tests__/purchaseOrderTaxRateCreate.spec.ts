import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

describe('procurement order tax rate create entry', () => {
  it('exposes per-line taxRate input and validation in the normal create dialog', () => {
    expect(listSource).toContain('taxRate?: number | string | null');
    expect(listSource).toContain('v-model="item.taxRate"');
    expect(listSource).toContain('placeholder="未配置"');
    expect(listSource).toContain('0%');
    expect(listSource).toContain('9%');
    expect(listSource).toContain('13%');
    expect(listSource).toContain('validateTaxRate(item.taxRate)');
    expect(listSource).toContain('税率必须是 0-100 之间的数字');
  });

  it('sends taxRate on normal and BOM create payload items without fake defaults', () => {
    expect(listSource).toContain('taxRate: normalizeTaxRateForPayload(i.taxRate)');
    expect(listSource).toContain('buildBomPurchaseOrderPayload(parent, children, orderDate)');
    expect(listSource).toContain('taxRate: normalizeTaxRateForPayload(tpl.taxRate)');
    expect(listSource).not.toContain('taxRate: 13');
  });
});

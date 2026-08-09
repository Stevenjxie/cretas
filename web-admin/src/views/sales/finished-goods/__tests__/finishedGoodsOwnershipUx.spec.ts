import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const detailSource = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');

describe('finished goods ownership UI', () => {
  it('shows ownership and owner customer in the list', () => {
    expect(listSource).toContain('label="库存归属"');
    expect(listSource).toContain('ownershipPresentation(row).ownershipLabel');
    expect(listSource).toContain('ownershipPresentation(row).customerLabel');
    expect(listSource).toContain('loadOwnerCustomers(rows)');
  });

  it('repeats ownership before quantities in the detail view', () => {
    const ownershipIndex = detailSource.indexOf('label="库存归属"');
    const quantityIndex = detailSource.indexOf('label="生产数量"');
    expect(ownershipIndex).toBeGreaterThan(-1);
    expect(quantityIndex).toBeGreaterThan(ownershipIndex);
    expect(detailSource).toContain('label="归属客户"');
    expect(detailSource).toContain('ownershipPresentation(batch).customerLabel');
  });
});

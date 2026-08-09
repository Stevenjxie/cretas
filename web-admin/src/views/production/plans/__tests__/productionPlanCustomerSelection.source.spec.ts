import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

describe('库存生产归属客户 UI 契约', () => {
  const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

  it('uses a searchable and clearable customer master-data select instead of free text', () => {
    expect(source).toContain('label="归属客户(选填)"');
    expect(source).toContain('v-model="planForm.customerId"');
    expect(source).toContain('@change="handlePlanCustomerChange"');
    expect(source).toContain('v-for="customer in selectablePlanCustomers"');
    expect(source).not.toContain('v-model="planForm.sourceCustomerName"');
  });

  it('submits ownership explicitly and derives the display name from the selected id', () => {
    expect(source).toContain("outputOwnership: 'COMPANY_OWNED'");
    expect(source).toContain('resolveProductionPlanCustomerSelection(customerId, customers.value)');
    expect(source).toContain('planForm.value.sourceCustomerName = selection.sourceCustomerName');
    expect(source).toContain('planForm.value.outputOwnership = selection.outputOwnership');
  });
});

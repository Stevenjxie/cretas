import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, 'list.vue'), 'utf8');

describe('customer create/edit progressive disclosure', () => {
  it('keeps the five required fields in the basic area and advanced data in one collapsed section', () => {
    for (const field of ['name', 'contactPerson', 'phone', 'shippingAddress', 'status']) {
      expect(source).toContain(`prop="${field}" required`);
    }
    expect(source).toContain('<el-collapse v-model="advancedSections"');
    expect(source).toContain('已填 {{ advancedFilledCount }} 项');
    expect(source.indexOf('label="客户类型"')).toBeLessThan(source.indexOf('<el-collapse v-model="advancedSections"'));
    expect(source.indexOf('label="邮箱"')).toBeGreaterThan(source.indexOf('<el-collapse v-model="advancedSections"'));
  });

  it('expands advanced validation errors and prevents duplicate submit', () => {
    expect(source).toContain('if (!formRef.value || submitting.value) return');
    expect(source).toContain("advancedSections.value = ['advanced']");
    expect(source).toContain('formRef.value?.scrollToField(advancedInvalid)');
  });

  it('shows and filters by business customer type instead of the lifecycle column', () => {
    expect(source).toContain('v-model="filterCustomerType"');
    expect(source).toContain('type: filterCustomerType.value || undefined');
    expect(source).toContain('<el-table-column label="客户类型"');
    expect(source).toContain('customerTypeLabel(row.type)');
    expect(source).not.toContain('<el-table-column label="生命周期"');
    expect(source).toContain("v-if=\"advancedFiltersVisible\"\n          v-model=\"filterCustomerStatus\"");
  });
});

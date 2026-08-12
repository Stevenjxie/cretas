import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, 'list.vue'), 'utf8');

describe('customer create/edit progressive disclosure', () => {
  // 2026-08-12: 本用例原来断言这五个字段都带 `required`, 拿「必填」当「在基础区」的代理。
  // 那是两件事被绑在了一起 —— Steve 要求把 联系人/联系电话/收货地址 改成选填时, 这条断言
  // 会红, 但它真正要守的**版面**并没有被破坏(三个字段仍在基础区)。
  // 现在按各自的语义分开断言: 位置用位置验, 必填用必填验。
  it('keeps the five basic fields above the collapse and advanced data in one collapsed section', () => {
    const collapseAt = source.indexOf('<el-collapse v-model="advancedSections"');
    expect(collapseAt).toBeGreaterThan(-1);

    // ① 版面: 五个基础字段都在折叠区**之前**
    for (const field of ['name', 'contactPerson', 'phone', 'shippingAddress', 'status']) {
      const at = source.indexOf(`prop="${field}"`);
      expect(at, `${field} 应当出现在表单里`).toBeGreaterThan(-1);
      expect(at, `${field} 应当在折叠区之前(基础区)`).toBeLessThan(collapseAt);
    }

    // ② 必填: 只有客户名称和状态是必填(后端 @NotBlank 只有 name; status 有默认值且业务上必须有)
    for (const field of ['name', 'status']) {
      expect(source).toContain(`prop="${field}" required`);
    }
    // 这三个是选填 —— 后端 DTO 只有 @Size, public.customers 三列 nullable=YES
    for (const field of ['contactPerson', 'phone', 'shippingAddress']) {
      expect(source, `${field} 应为选填`).not.toContain(`prop="${field}" required`);
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

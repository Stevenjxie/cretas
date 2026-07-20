import { describe, expect, it } from 'vitest';
import {
  countFilledCustomerAdvancedFields,
  customerTypeLabel,
  isCustomerAdvancedField,
  isSupportedCustomerPhone,
  normalizeCustomerPayload,
} from './customerFormModel';

describe('customer progressive-disclosure form model', () => {
  it('accepts mobile, landline with extension and international phones', () => {
    expect(isSupportedCustomerPhone('13800138000')).toBe(true);
    expect(isSupportedCustomerPhone('021-12345678 转 806')).toBe(true);
    expect(isSupportedCustomerPhone('(010) 12345678 ext. 66')).toBe(true);
    expect(isSupportedCustomerPhone('+65 6123 4567')).toBe(true);
    expect(isSupportedCustomerPhone('123')).toBe(false);
  });

  it('writes the five required fields but omits untouched advanced defaults', () => {
    const payload = normalizeCustomerPayload({
      name: ' 客户A ', contactPerson: ' 张三 ', phone: ' 021-12345678 ',
      shippingAddress: ' 上海市 ', status: 'ACTIVE', type: '', customerStatus: '',
      importance: '', creditStatus: '', defaultTaxRate: null, creditLimit: 0,
    }, ['creditLimit']);
    expect(payload).toMatchObject({
      name: '客户A', contactPerson: '张三', phone: '021-12345678',
      shippingAddress: '上海市', status: 'ACTIVE', creditLimit: 0,
    });
    expect(payload).not.toHaveProperty('customerStatus');
    expect(payload).not.toHaveProperty('importance');
    expect(payload).not.toHaveProperty('creditStatus');
  });

  it('counts advanced values and exposes business customer type labels', () => {
    expect(countFilledCustomerAdvancedFields({ email: 'a@b.com', defaultTaxRate: 0, source: '' })).toBe(2);
    expect(isCustomerAdvancedField('email')).toBe(true);
    expect(isCustomerAdvancedField('notes')).toBe(false);
    expect(customerTypeLabel('RESTAURANT')).toBe('餐饮企业');
    expect(customerTypeLabel('')).toBe('未设置');
  });
});

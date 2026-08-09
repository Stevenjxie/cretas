import { describe, expect, it } from 'vitest';
import type { Customer } from '@/api/customer';
import {
  isCustomerOwnedBatch,
  ownershipPresentation,
} from '../useFinishedGoodsOwnership';

const customer: Customer = {
  id: 'customer-1',
  factoryId: 'F006',
  customerCode: 'CUS-001',
  name: '测试客户',
};

describe('finished goods ownership presentation', () => {
  it('shows company inventory when no customer owner is recorded', () => {
    expect(ownershipPresentation({ ownership: 'COMPANY_OWNED' }, undefined, 'idle'))
      .toEqual({
        isCustomerOwned: false,
        ownershipLabel: '公司库存',
        customerLabel: '不限定客户',
        tagType: 'success',
      });
  });

  it('shows the customer name and code for customer-owned inventory', () => {
    expect(ownershipPresentation(
      { ownership: 'CUSTOMER_OWNED', ownerCustomerId: customer.id },
      customer,
      'loaded',
    )).toMatchObject({
      ownershipLabel: '客户专属',
      customerLabel: '测试客户（CUS-001）',
      tagType: 'warning',
    });
  });

  it('fails closed when customer data cannot be loaded', () => {
    expect(ownershipPresentation(
      { ownership: 'CUSTOMER_OWNED', ownerCustomerId: customer.id },
      null,
      'failed',
    )).toMatchObject({
      ownershipLabel: '客户专属',
      customerLabel: '客户资料加载失败，请刷新',
      tagType: 'danger',
    });
  });

  it('treats an owner customer id as customer-owned even if the enum is inconsistent', () => {
    expect(isCustomerOwnedBatch({
      ownership: 'COMPANY_OWNED',
      ownerCustomerId: customer.id,
    })).toBe(true);
  });

  it('does not hide a customer-owned record that is missing its owner id', () => {
    expect(ownershipPresentation({ ownership: 'CUSTOMER_OWNED' }, undefined, 'idle'))
      .toMatchObject({
        ownershipLabel: '客户专属',
        customerLabel: '未记录归属客户',
        tagType: 'danger',
      });
  });
});

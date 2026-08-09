import { describe, expect, it } from 'vitest';
import {
  productionPlanCustomerLabel,
  resolveProductionPlanCustomerSelection,
  selectableProductionPlanCustomers,
} from '../productionPlanCustomerSelection';

const customers = [
  { id: 'c-active', name: '客户甲', customerCode: 'C001', isActive: true },
  { id: 'c-inactive', name: '客户乙', customerCode: 'C002', isActive: false },
  { id: 'c-company', companyName: '客户丙', isActive: true },
  { id: '', name: '无效客户', isActive: true },
];

describe('productionPlanCustomerSelection', () => {
  it('keeps an empty selection as company-owned inventory', () => {
    expect(resolveProductionPlanCustomerSelection('', customers)).toEqual({
      customerId: '',
      sourceCustomerName: '',
      outputOwnership: 'COMPANY_OWNED',
    });
  });

  it('maps an active customer id to customer-owned production truth', () => {
    expect(resolveProductionPlanCustomerSelection('c-active', customers)).toEqual({
      customerId: 'c-active',
      sourceCustomerName: '客户甲',
      outputOwnership: 'CUSTOMER_OWNED',
    });
  });

  it('fails closed for inactive or unknown customer ids', () => {
    expect(resolveProductionPlanCustomerSelection('c-inactive', customers).outputOwnership).toBe('COMPANY_OWNED');
    expect(resolveProductionPlanCustomerSelection('missing', customers).customerId).toBe('');
  });

  it('only exposes active named customer master data and labels names before codes', () => {
    expect(selectableProductionPlanCustomers(customers).map((customer) => customer.id)).toEqual([
      'c-active',
      'c-company',
    ]);
    expect(productionPlanCustomerLabel(customers[0])).toBe('客户甲（C001）');
    expect(productionPlanCustomerLabel(customers[2])).toBe('客户丙');
  });
});

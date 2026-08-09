import { customerMatchesQuery } from '../../../components/common/customerSelectorSearch';

describe('customerMatchesQuery', () => {
  const customerWithoutOptionalFields = {
    name: 'SOP-20260730-01-测试客户',
    code: undefined,
    contactPerson: null,
  };

  it('finds a customer by name when code and contact are absent', () => {
    expect(customerMatchesQuery(customerWithoutOptionalFields, '测试客户')).toBe(true);
  });

  it('does not throw or match when all searchable fields are absent', () => {
    expect(customerMatchesQuery({}, '客户')).toBe(false);
  });

  it('keeps empty-query behavior and matches every customer', () => {
    expect(customerMatchesQuery({}, '   ')).toBe(true);
  });

  it('matches code and contact without case sensitivity', () => {
    expect(customerMatchesQuery({ code: 'F006-CUST', contactPerson: 'Alice' }, 'cust')).toBe(true);
    expect(customerMatchesQuery({ code: 'F006-CUST', contactPerson: 'Alice' }, 'ALICE')).toBe(true);
  });
});

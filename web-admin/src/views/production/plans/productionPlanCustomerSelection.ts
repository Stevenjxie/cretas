export type ProductionPlanOutputOwnership = 'COMPANY_OWNED' | 'CUSTOMER_OWNED';

export interface ProductionPlanCustomerOption {
  id?: unknown;
  name?: unknown;
  companyName?: unknown;
  customerCode?: unknown;
  isActive?: unknown;
}

export interface ProductionPlanCustomerSelection {
  customerId: string;
  sourceCustomerName: string;
  outputOwnership: ProductionPlanOutputOwnership;
}

export function productionPlanCustomerName(customer: ProductionPlanCustomerOption): string {
  return String(customer.name || customer.companyName || '').trim();
}

export function productionPlanCustomerLabel(customer: ProductionPlanCustomerOption): string {
  const name = productionPlanCustomerName(customer);
  const code = String(customer.customerCode || '').trim();
  return code ? `${name}（${code}）` : name;
}

export function selectableProductionPlanCustomers(
  customers: ProductionPlanCustomerOption[],
): ProductionPlanCustomerOption[] {
  return customers.filter((customer) => (
    customer.isActive !== false
    && String(customer.id || '').trim() !== ''
    && productionPlanCustomerName(customer) !== ''
  ));
}

export function resolveProductionPlanCustomerSelection(
  customerId: string | null | undefined,
  customers: ProductionPlanCustomerOption[],
): ProductionPlanCustomerSelection {
  const normalizedId = String(customerId || '').trim();
  if (!normalizedId) {
    return {
      customerId: '',
      sourceCustomerName: '',
      outputOwnership: 'COMPANY_OWNED',
    };
  }
  const customer = customers.find((candidate) => String(candidate.id || '') === normalizedId);
  if (!customer || customer.isActive === false || !productionPlanCustomerName(customer)) {
    return {
      customerId: '',
      sourceCustomerName: '',
      outputOwnership: 'COMPANY_OWNED',
    };
  }
  return {
    customerId: normalizedId,
    sourceCustomerName: productionPlanCustomerName(customer),
    outputOwnership: 'CUSTOMER_OWNED',
  };
}

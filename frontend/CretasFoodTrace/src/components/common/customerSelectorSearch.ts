export interface SearchableCustomer {
  name?: string | null;
  code?: string | null;
  contactPerson?: string | null;
}

export function customerMatchesQuery(customer: SearchableCustomer, query: string): boolean {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) return true;

  return [customer.name, customer.code, customer.contactPerson].some(
    (value) => typeof value === 'string' && value.toLowerCase().includes(normalizedQuery),
  );
}

export type DemoTenant = 'factory' | 'rest' | 'logistics';

function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : undefined;
  }
  return typeof value === 'string' ? value : undefined;
}

export function resolveDemoTenant(query: Record<string, unknown>): DemoTenant | null {
  const raw = firstQueryValue(query.tenant ?? query.type)?.toLowerCase();
  if (raw === 'rest' || raw === 'restaurant' || raw === 'catering') return 'rest';
  if (raw === 'factory' || raw === 'food') return 'factory';
  if (raw === 'logistics' || raw === 'logistic' || raw === 'transport') return 'logistics';
  return null;
}

export function resolveDemoRedirect(query: Record<string, unknown>, tenant?: DemoTenant | null): string {
  const redirect = firstQueryValue(query.redirect);
  if (redirect && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect;
  }
  if (tenant === 'logistics') return '/scheduling/logistics-demo';
  return '/dashboard';
}

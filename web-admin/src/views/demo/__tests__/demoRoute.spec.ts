import { describe, expect, it } from 'vitest';
import { resolveDemoRedirect, resolveDemoTenant } from '../demoRoute';

describe('demoRoute', () => {
  it('resolves restaurant demo tenants', () => {
    expect(resolveDemoTenant({ tenant: 'rest' })).toBe('rest');
    expect(resolveDemoTenant({ tenant: 'restaurant' })).toBe('rest');
    expect(resolveDemoTenant({ type: 'catering' })).toBe('rest');
  });

  it('resolves factory demo tenants', () => {
    expect(resolveDemoTenant({ tenant: 'factory' })).toBe('factory');
    expect(resolveDemoTenant({ type: 'food' })).toBe('factory');
  });

  it('returns null when the tenant is missing or invalid', () => {
    expect(resolveDemoTenant({})).toBeNull();
    expect(resolveDemoTenant({ tenant: 'unknown' })).toBeNull();
  });

  it('resolves an internal redirect', () => {
    expect(resolveDemoRedirect({ redirect: '/smart-bi/analysis?mode=chat' })).toBe('/smart-bi/analysis?mode=chat');
  });

  it('rejects external redirects', () => {
    expect(resolveDemoRedirect({ redirect: 'https://example.com' })).toBe('/dashboard');
    expect(resolveDemoRedirect({ redirect: '//example.com' })).toBe('/dashboard');
  });
});

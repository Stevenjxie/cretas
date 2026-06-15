/**
 * Sprint 2 Track I — canViewPriceStore unit tests.
 * Verifies PRICE_VIEW_ROLES gate mirrors web-admin permission store.
 */
import { canViewPriceForRole, PRICE_VIEW_ROLES } from '../../../store/canViewPriceStore';

describe('canViewPriceForRole', () => {
  it.each([
    'factory_super_admin',
    'platform_admin',
    'procurement_manager',
    'finance_manager',
    'cashier',
    'sales_manager',
    'dispatcher',
    'production_manager',
    'restaurant_manager',
    'permission_admin',
    'department_admin',
  ])('allows price view for role %s', (role) => {
    expect(canViewPriceForRole(role)).toBe(true);
  });

  it.each([
    'warehouse_manager',
    'quality_inspector',
    'workshop_supervisor',
    'operator',
    'unactivated',
    '',
  ])('denies price view for role %s', (role) => {
    expect(canViewPriceForRole(role)).toBe(false);
  });

  it('denies for null/undefined', () => {
    expect(canViewPriceForRole(null)).toBe(false);
    expect(canViewPriceForRole(undefined)).toBe(false);
  });

  it('PRICE_VIEW_ROLES contains exactly 11 roles (includes cashier payment flow)', () => {
    expect(PRICE_VIEW_ROLES.size).toBe(11);
  });
});

import { useAuthStore } from './authStore';

/**
 * RN 镜像 web-admin/src/store/modules/permission.ts:PRICE_VIEW_ROLES (line 228-239).
 * Mirrors backend PermissionServiceImpl.PRICE_VIEW_ROLES for procurement:price:view.
 * Backend strips price values to null for any role outside this list; frontend uses
 * it to hide price-related summary stats (otherwise仓管 sees 0/null misleading).
 *
 * Sprint 1 Track C shipped this Set web-admin only — Sprint 2 Track I (U-FOOTER-1)
 * brings parity to RN, since StickyFooterSummary needs the same gate for 金额 stats.
 */
const PRICE_VIEW_ROLES: ReadonlySet<string> = new Set([
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
]);

/**
 * Returns true if current logged-in user's role can view price fields.
 *
 * Usage:
 *   const canViewPrice = useCanViewPrice();
 *   const visibleStats = stats.filter(s => !s.canViewPrice || canViewPrice);
 *
 * Reactive: subscribes to useAuthStore so role change triggers re-render.
 */
export function useCanViewPrice(): boolean {
  const user = useAuthStore((state) => state.user);
  if (!user) return false;
  const role =
    user.userType === 'platform' && 'platformUser' in user
      ? user.platformUser.role
      : user.userType === 'factory' && 'factoryUser' in user
        ? user.factoryUser.role
        : null;
  return role != null && PRICE_VIEW_ROLES.has(role);
}

/**
 * Non-hook variant for use outside React (e.g. service layer, request interceptor).
 */
export function canViewPriceForRole(role: string | null | undefined): boolean {
  return role != null && PRICE_VIEW_ROLES.has(role);
}

export { PRICE_VIEW_ROLES };
